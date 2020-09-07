package com.shuangyueliao.chat.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shuangyueliao.chat.entity.Account;
import com.shuangyueliao.chat.entity.UserInfo;
import com.shuangyueliao.chat.mapper.AccountMapper;
import com.shuangyueliao.chat.proto.ChatProto;
import com.shuangyueliao.chat.queue.OfflineInfoTransmit;
import com.shuangyueliao.chat.queue.rabbitmq.RabbitmqOfflineInfoHelper;
import com.shuangyueliao.chat.util.BlankUtil;
import com.shuangyueliao.chat.util.NettyUtil;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * @author shuangyueliao
 * @description Channel的管理器以及user管理工具类
 */
@Component
public class UserInfoManager {
    private static final Logger logger = LoggerFactory.getLogger(UserInfoManager.class);
    //可重入读写锁
    public static ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock(true);

    //用来保存通道和用户信息的map，一个用户对应一个通道。
    public static ConcurrentMap<Channel, UserInfo> userInfos = new ConcurrentHashMap<>();
    private static AtomicInteger userCount = new AtomicInteger(0);

    @Resource
    //用户dao层mapper
    private AccountMapper accountMapper;

    private static AccountMapper accountMapperStatic;

    @Autowired
    private OfflineInfoTransmit offlineInfoTransmit;
    private static OfflineInfoTransmit offlineInfoTransmitStatic;

    public static void p2p(Integer uid, String nick, String other, String message) {
        if (!BlankUtil.isBlank(message)) {
            try {
                rwLock.readLock().lock();
                message = "[来自于用户" + nick +"的消息]:" + message;
                Set<Channel> keySet = userInfos.keySet();
                for (Channel ch : keySet) {
                    UserInfo userInfo = userInfos.get(ch);
                    // 找出对应channel进行发送
                    if (userInfo == null || !userInfo.isAuth() || !userInfo.getNick().equals(other)) {
                        continue;
                    }
                    //在线用户的个人对个人通信直接走channel，不走第三方中间件和其它
                    ch.writeAndFlush(new TextWebSocketFrame(ChatProto.buildMessProto(userInfo.getId(), userInfo.getUsername(), message)));
                    return;
                }
                //不在线，离线推送
                LambdaQueryWrapper<Account> lambdaQueryWrapper = new LambdaQueryWrapper<>();
                lambdaQueryWrapper.eq(Account::getUsername, other);
                Account account = accountMapperStatic.selectOne(lambdaQueryWrapper);
                if (account != null) {
                    offlineInfoTransmitStatic.pushP2P(account.getId(), message);
                }
            } finally {
                rwLock.readLock().unlock();
            }
        }
    }

    @PostConstruct
    /**
     * @PostConstruct修饰的方法会在服务器加载Servlet的时候运行，并且只会被服务器执行一次
     */
    public void init() {
        accountMapperStatic = accountMapper;
        offlineInfoTransmitStatic = offlineInfoTransmit;
    }

    //新增一个通道
    public static void addChannel(Channel channel) {
        //获取远程地址
        String remoteAddr = NettyUtil.parseChannelRemoteAddr(channel);
        System.out.println("addChannel:" + remoteAddr);
        if (!channel.isActive()) {
            logger.error("channel is not active, address: {}", remoteAddr);
        }
        UserInfo userInfo = new UserInfo();
        userInfo.setAddr(remoteAddr);
        userInfo.setChannel(channel);
        userInfo.setTime(System.currentTimeMillis());
        userInfos.put(channel, userInfo);
    }

    /**
     * 保存一个user
     * @param channel 这个用户的通道
     * @param nick 用户的用户名
     * @param password 用户的密码
     * @return
     */
    public static boolean saveUser(Channel channel, String nick, String password) {
        UserInfo userInfo = userInfos.get(channel);
        if (userInfo == null) {
            return false;
        }
        if (!channel.isActive()) {
            logger.error("channel is not active, address: {}, nick: {}", userInfo.getAddr(), nick);
            return false;
        }
        // 验证用户名和密码
        if (nick == null || password == null) {
            return false;
        }
        //数据库查询
        LambdaQueryWrapper<Account> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(Account::getUsername, nick).eq(Account::getPassword, password);
        Account account = accountMapperStatic.selectOne(lambdaQueryWrapper);

        if (account == null) {
            return false;
        }
        // 增加一个认证用户
        userCount.incrementAndGet();
        userInfo.setNick(nick);
        userInfo.setAuth(true);
        userInfo.setId(account.getId());
        userInfo.setUsername(account.getUsername());
        userInfo.setGroupNumber(account.getGroupNumber());
        userInfo.setTime(System.currentTimeMillis());
        try {
            offlineInfoTransmitStatic.addNewqueue(account);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        // 注册该用户推送消息的通道
        offlineInfoTransmitStatic.registerPull(channel);
        return true;
    }

    /**
     * 从缓存中移除Channel，并且关闭Channel
     *
     * @param channel
     */
    public static void removeChannel(Channel channel) {
        try {
            logger.warn("channel will be remove, address is :{}", NettyUtil.parseChannelRemoteAddr(channel));
            //加上读写锁保证移除channel时，避免channel关闭时，还有别的线程对其操作，造成错误
            rwLock.writeLock().lock();
            channel.close();
            UserInfo userInfo = userInfos.get(channel);
            if (userInfo != null) {
                if (userInfo.isAuth()) {
                    offlineInfoTransmitStatic.unregisterPull(channel);
                    // 减去一个认证用户
                    userCount.decrementAndGet();
                }
                userInfos.remove(channel);
            }
        } finally {
            rwLock.writeLock().unlock();
        }

    }

    /**
     * 在同一个群中广播普通消息
     *
     * @param message
     */
    public static void broadcastMess(int uid, String nick, String message, String groupNumber) {
        if (!BlankUtil.isBlank(message)) {
            try {
                rwLock.readLock().lock();
                offlineInfoTransmitStatic.pushGroup(groupNumber, message);
            } finally {
                rwLock.readLock().unlock();
            }
        }
    }

    /**
     * 广播系统消息
     */
    public static void broadCastInfo(int code, Object mess) {
        try {
            rwLock.readLock().lock();
            Set<Channel> keySet = userInfos.keySet();
            for (Channel ch : keySet) {
                UserInfo userInfo = userInfos.get(ch);
                if (userInfo == null || !userInfo.isAuth()) {
                    continue;
                }
                ch.writeAndFlush(new TextWebSocketFrame(ChatProto.buildSystProto(code, mess)));
            }
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 向所有在线用户广播ping
     */
    public static void broadCastPing() {
        try {
            rwLock.readLock().lock();
            logger.info("broadCastPing userCount: {}", userCount.intValue());
            Set<Channel> keySet = userInfos.keySet();
            for (Channel ch : keySet) {
                UserInfo userInfo = userInfos.get(ch);
                //如果channel是没有用户信息或者没有授权的用户则跳过
                if (userInfo == null || !userInfo.isAuth()) {
                    continue;
                }
                ch.writeAndFlush(new TextWebSocketFrame(ChatProto.buildPingProto()));
            }
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 发送系统消息
     *
     * @param code
     * @param mess
     */
    public static void sendInfo(Channel channel, int code, Object mess) {
        channel.writeAndFlush(new TextWebSocketFrame(ChatProto.buildSystProto(code, mess)));
    }

    public static void sendPong(Channel channel) {
        channel.writeAndFlush(new TextWebSocketFrame(ChatProto.buildPongProto()));
    }

    /**
     * 扫描并关闭失效的Channel
     */
    public static void scanNotActiveChannel() {
        Set<Channel> keySet = userInfos.keySet();
        for (Channel ch : keySet) {
            UserInfo userInfo = userInfos.get(ch);
            if (userInfo == null) {
                continue;
            }
            //如果channel没有打开或者激活或者验证用户信息时间超过10s就认为这是一个无效channel，应该移除
            if (!ch.isOpen() || !ch.isActive() || (!userInfo.isAuth() &&
                    (System.currentTimeMillis() - userInfo.getTime()) > 10000)) {
                removeChannel(ch);
            }
        }
    }

    /**
     * 根据一个通道获取一个用户的信息
     * @param channel
     * @return
     */
    public static UserInfo getUserInfo(Channel channel) {
        return userInfos.get(channel);
    }


    /**
     * 获取当前在线用户的全部信息
     * @return
     */
    public static ConcurrentMap<Channel, UserInfo> getUserInfos() {
        return userInfos;
    }


    /**
     * 获取当前用户数量
     * @return
     */
    public static int getAuthUserCount() {
        return userCount.get();
    }

    /**
     * 更新验证用户信息时间
     * @param channel
     */
    public static void updateUserTime(Channel channel) {
        UserInfo userInfo = getUserInfo(channel);
        if (userInfo != null) {
            userInfo.setTime(System.currentTimeMillis());
        }
    }


    public static Boolean joinAroom(Channel channel,String groupNumber)  {
        UserInfo userInfo = getUserInfo(channel);
        userInfo.setGroupNumber(groupNumber);
        Boolean res = null;
        try {
            res = offlineInfoTransmitStatic.setQueueBindRoom(userInfo);
        } catch (IOException e) {
            return false;
        }

        return res;
    }

}
