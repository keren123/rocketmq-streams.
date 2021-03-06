/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.streams.lease.service.impl;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.rocketmq.streams.common.utils.DateUtil;
import org.apache.rocketmq.streams.common.utils.MapKeyUtil;
import org.apache.rocketmq.streams.lease.model.LeaseInfo;
import org.apache.rocketmq.streams.lease.service.ILeaseService;

public class LeaseServiceImpl extends BasedLesaseImpl {

    private static final Log LOG = LogFactory.getLog(LeaseServiceImpl.class);

    private transient ConcurrentHashMap<String, HoldLockTask> holdLockTasks = new ConcurrentHashMap();

    protected ConcurrentHashMap<String, HoldLockFunture> seizeLockingFuntures = new ConcurrentHashMap<>();
    //如果是抢占锁状态中，则不允许申请锁

    public LeaseServiceImpl() {
        super();
    }

    /**
     * 尝试获取锁，可以等待waitTime，如果到点未返回，则直接返回。如果是-1，则一直等待
     *
     * @param name       业务名称
     * @param lockerName 锁名称
     * @param waitTime   等待时间，是微秒单位
     * @return
     */
    @Override
    public boolean tryLocker(String name, String lockerName, long waitTime) {
        return tryLocker(name, lockerName, waitTime, ILeaseService.DEFALUT_LOCK_TIME);
    }

    @Override
    public boolean tryLocker(String name, String lockerName, long waitTime, int lockTimeSecond) {
        long now = System.currentTimeMillis();
        boolean success = lock(name, lockerName, lockTimeSecond);
        while (!success) {
            if (waitTime > -1 && (System.currentTimeMillis() - now > waitTime)) {
                break;
            }
            success = lock(name, lockerName, lockTimeSecond);
            if (success) {
                return success;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                LOG.error("LeaseServiceImpl try locker error", e);
            }
        }
        return success;

    }

    @Override
    public boolean lock(String name, String lockerName) {
        return lock(name, lockerName, ILeaseService.DEFALUT_LOCK_TIME);
    }

    @Override
    public boolean lock(String name, String lockerName, int leaseSecond) {
        lockerName = createLockName(name, lockerName);
        Future future = seizeLockingFuntures.get(lockerName);
        if (future != null && ((HoldLockFunture)future).isDone == false) {
            return false;
        }
        Date nextLeaseDate =
            DateUtil.addSecond(new Date(), leaseSecond);// 默认锁定5分钟，用完需要立刻释放.如果时间不同步，可能导致锁失败
        return tryGetLease(lockerName, nextLeaseDate);
    }

    @Override
    public boolean unlock(String name, String lockerName) {
        // LOG.info("LeaseServiceImpl unlock,name:" + name);
        lockerName = createLockName(name, lockerName);
        LeaseInfo validateLeaseInfo = queryValidateLease(lockerName);
        if (validateLeaseInfo == null) {
            LOG.warn("LeaseServiceImpl unlock,validateLeaseInfo is null,lockerName:" + lockerName);
        }
        if (validateLeaseInfo != null && validateLeaseInfo.getLeaseUserIp().equals(getSelfUser())) {
            validateLeaseInfo.setStatus(0);
            updateDBLeaseInfo(validateLeaseInfo);
        }
        HoldLockTask holdLockTask = holdLockTasks.remove(lockerName);
        if (holdLockTask != null) {
            holdLockTask.close();
        }
        leaseName2Date.remove(lockerName);
        return false;
    }

    /**
     * 如果有锁，则一直持有，如果不能获取，则结束。和租约不同，租约是没有也会尝试重试，一备对方挂机，自己可以接手工作
     *
     * @param name
     * @param secondeName
     * @param lockTimeSecond 获取锁的时间
     * @return
     */
    @Override
    public boolean holdLock(String name, String secondeName, int lockTimeSecond) {
        if (hasHoldLock(name, secondeName)) {
            return true;
        }
        synchronized (this) {
            if (hasHoldLock(name, secondeName)) {
                return true;
            }
            String lockerName = createLockName(name, secondeName);
            Date nextLeaseDate =
                DateUtil.addSecond(new Date(), lockTimeSecond);
            boolean success = tryGetLease(lockerName, nextLeaseDate);// 申请锁，锁的时间是leaseTerm
            if (!success) {
                return false;
            }
            leaseName2Date.put(lockerName, nextLeaseDate);

            if (!holdLockTasks.containsKey(lockerName)) {
                HoldLockTask holdLockTask = new HoldLockTask(lockTimeSecond, lockerName, this);
                holdLockTask.start();
                holdLockTasks.putIfAbsent(lockerName, holdLockTask);
            }
        }

        return true;
    }

    /**
     * 是否持有锁，不访问数据库，直接看本地
     *
     * @param name
     * @param secondeName
     * @return
     */
    @Override
    public boolean hasHoldLock(String name, String secondeName) {
        String lockerName = createLockName(name, secondeName);
        return hasLease(lockerName);
    }

    @Override
    public List<LeaseInfo> queryLockedInstanceByNamePrefix(String name, String lockerNamePrefix) {
        String leaseNamePrefix = MapKeyUtil.createKey(name, lockerNamePrefix);
        return queryValidateLeaseByNamePrefix(leaseNamePrefix);
    }

    private String createLockName(String name, String lockerName) {
        return MapKeyUtil.createKey(name, lockerName);
    }

    private class HoldLockTask extends ApplyTask {
        protected volatile boolean isContinue = true;
        protected LeaseServiceImpl leaseService;
        protected ScheduledExecutorService scheduledExecutor;

        public HoldLockTask(int leaseTerm, String name, LeaseServiceImpl leaseService) {
            super(leaseTerm, name);
            this.leaseService = leaseService;
            scheduledExecutor = new ScheduledThreadPoolExecutor(1);

        }

        public void start() {
            scheduledExecutor.scheduleWithFixedDelay(this, leaseTerm / 2, leaseTerm / 2, TimeUnit.SECONDS);
        }

        public void close() {
            isContinue = false;
            if (scheduledExecutor != null) {
                scheduledExecutor.shutdown();
            }
        }

        public boolean isContinue() {
            return isContinue;
        }

        @Override
        public void run() {
            try {
                if (!isContinue) {
                    return;
                }
                Date leaseDate = applyLeaseTask(leaseTerm, name, new AtomicBoolean(false));
                if (leaseDate != null) {
                    leaseName2Date.put(name, leaseDate);
                    LOG.debug("LeaseServiceImpl, name: " + name + " " + getSelfUser() + " 续约锁成功, 租约到期时间为 "
                        + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(leaseDate));
                } else {
                    isContinue = false;
                    synchronized (leaseService) {
                        holdLockTasks.remove(name);
                    }
                    LOG.info("LeaseServiceImpl name: " + name + " " + getSelfUser() + " 续约锁失败，续锁程序会停止");
                }
            } catch (Exception e) {
                isContinue = false;
                LOG.error(" LeaseServiceImpl name: " + name + "  " + getSelfUser() + " 续约锁出现异常，续锁程序会停止", e);
            }

        }

    }

    /**
     * 抢占锁的future，必须等锁超时才能继续获取锁
     */
    protected class HoldLockFunture implements Future<Boolean> {
        private volatile boolean isDone = false;
        private volatile Date date = null;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            throw new RuntimeException("can not cancel");
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            if (date != null && System.currentTimeMillis() - date.getTime() >= 0) {
                isDone = true;
                return isDone;
            }
            return false;
        }

        @Override
        public Boolean get() throws InterruptedException, ExecutionException {
            while (!isDone()) {
                Thread.sleep(1000);
            }
            return true;
        }

        private long startTime = System.currentTimeMillis();

        @Override
        public Boolean get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {

            throw new RuntimeException("can not support timeout ");
        }

    }
}
