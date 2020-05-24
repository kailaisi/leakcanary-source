/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.leakcanary;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.MessageQueue;
import android.support.annotation.NonNull;
import java.util.concurrent.TimeUnit;

import static com.squareup.leakcanary.Retryable.Result.RETRY;

/**
 * {@link WatchExecutor} suitable for watching Android reference leaks. This executor waits for the
 * main thread to be idle then posts to a serial background thread with the delay specified by
 * {@link AndroidRefWatcherBuilder#watchDelay(long, TimeUnit)}.
 */
public final class AndroidWatchExecutor implements WatchExecutor {

  static final String LEAK_CANARY_THREAD_NAME = "LeakCanary-Heap-Dump";
  private final Handler mainHandler;
  private final Handler backgroundHandler;
  //默认5秒
  private final long initialDelayMillis;
  private final long maxBackoffFactor;

  public AndroidWatchExecutor(long initialDelayMillis) {
    mainHandler = new Handler(Looper.getMainLooper());
    //HandlerThread是一种拥有Looper的线程
    HandlerThread handlerThread = new HandlerThread(LEAK_CANARY_THREAD_NAME);
    handlerThread.start();
    backgroundHandler = new Handler(handlerThread.getLooper());
    this.initialDelayMillis = initialDelayMillis;
    maxBackoffFactor = Long.MAX_VALUE / initialDelayMillis;
  }

  @Override public void execute(@NonNull Retryable retryable) {
    //如果当前线程是主线程，则直接执行waitForIdl
    if (Looper.getMainLooper().getThread() == Thread.currentThread()) {
      waitForIdle(retryable, 0);
    } else {
      //如果不是主线程，则通过Handler机制，将waitForIdle放入到主线程去执行
      postWaitForIdle(retryable, 0);
    }
  }

  private void postWaitForIdle(final Retryable retryable, final int failedAttempts) {
    //通过Handler机制，将waitForIdle发送到主线程执行
    mainHandler.post(new Runnable() {
      @Override public void run() {
        waitForIdle(retryable, failedAttempts);
      }
    });
  }

  private void waitForIdle(final Retryable retryable, final int failedAttempts) {
    // This needs to be called from the main thread.
    //当messagequeue闲置时，增加一个处理。这种方法主要是为了提升性能，不会影响我们正常的应用流畅度
    //这个方法会在主线程执行，所以postToBackgroundWithDelay会在主线程执行
    Looper.myQueue().addIdleHandler(new MessageQueue.IdleHandler() {
      @Override public boolean queueIdle() {
        postToBackgroundWithDelay(retryable, failedAttempts);
        return false;
      }
    });
  }

  private void postToBackgroundWithDelay(final Retryable retryable, final int failedAttempts) {
    //计算补偿因子。如果返回了重试的话，这个failedAttempts回增加，会使得方法的执行时间延迟时间增加。
    //比如说第一次，演示5秒执行，但是执行结果为RETRY，那么下一次就是延迟10秒来执行了
    long exponentialBackoffFactor = (long) Math.min(Math.pow(2, failedAttempts), maxBackoffFactor);
    //计算延迟时间
    long delayMillis = initialDelayMillis * exponentialBackoffFactor;
    //backgroundHandler会将run方法中的代码放在一个新的线程中去执行。
    backgroundHandler.postDelayed(new Runnable() {
      @Override public void run() {
        Retryable.Result result = retryable.run();
        if (result == RETRY) {
          postWaitForIdle(retryable, failedAttempts + 1);
        }
      }
    }, delayMillis);
  }
}
