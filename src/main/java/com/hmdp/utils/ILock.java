package com.hmdp.utils;

public interface ILock {
    boolean tryLock(long timeoutSecond);
    void unlock();
   }
