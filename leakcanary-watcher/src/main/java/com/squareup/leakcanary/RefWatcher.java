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

import java.io.File;
import java.lang.ref.ReferenceQueue;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

import static com.squareup.leakcanary.HeapDumper.RETRY_LATER;
import static com.squareup.leakcanary.Preconditions.checkNotNull;
import static com.squareup.leakcanary.Retryable.Result.DONE;
import static com.squareup.leakcanary.Retryable.Result.RETRY;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Watches references that should become weakly reachable. When the {@link RefWatcher} detects that
 * a reference might not be weakly reachable when it should, it triggers the {@link HeapDumper}.
 *
 * <p>This class is thread-safe: you can call {@link #watch(Object)} from any thread.
 */
public final class RefWatcher {

    public static final RefWatcher DISABLED = new RefWatcherBuilder<>().build();
    //用于执行内存泄漏的检测
    private final WatchExecutor watchExecutor;
    //查询正在调试中，如果正在调试，那么不会进行检查的判断
    private final DebuggerControl debuggerControl;
    //在确定泄漏之前，会执行一次gc
    private final GcTrigger gcTrigger;
    //用于dump出内存泄漏对应的堆信息
    private final HeapDumper heapDumper;
    private final HeapDump.Listener heapdumpListener;
    //建造者模式，用于构造对应的heapDumper
    private final HeapDump.Builder heapDumpBuilder;
    //要检测的内存所对应的key的集合
    private final Set<String> retainedKeys;
    //弱引用所关联的queue
    private final ReferenceQueue<Object> queue;

    RefWatcher(WatchExecutor watchExecutor, DebuggerControl debuggerControl, GcTrigger gcTrigger,
               HeapDumper heapDumper, HeapDump.Listener heapdumpListener, HeapDump.Builder heapDumpBuilder) {
        this.watchExecutor = checkNotNull(watchExecutor, "watchExecutor");
        this.debuggerControl = checkNotNull(debuggerControl, "debuggerControl");
        this.gcTrigger = checkNotNull(gcTrigger, "gcTrigger");
        this.heapDumper = checkNotNull(heapDumper, "heapDumper");
        this.heapdumpListener = checkNotNull(heapdumpListener, "heapdumpListener");
        this.heapDumpBuilder = heapDumpBuilder;
        retainedKeys = new CopyOnWriteArraySet<>();
        queue = new ReferenceQueue<>();
    }

    /**
     * Identical to {@link #watch(Object, String)} with an empty string reference name.
     *
     * @see #watch(Object, String)
     */
    public void watch(Object watchedReference) {
        //重载方法
        watch(watchedReference, "");
    }

    /**
     * Watches the provided references and checks if it can be GCed. This method is non blocking,
     * the check is done on the {@link WatchExecutor} this {@link RefWatcher} has been constructed
     * with.
     *
     * @param referenceName An logical identifier for the watched object.
     */
    public void watch(Object watchedReference, String referenceName) {
        if (this == DISABLED) {
            return;
        }
        //保证watch的对象不为空
        checkNotNull(watchedReference, "watchedReference");
        checkNotNull(referenceName, "referenceName");
        final long watchStartNanoTime = System.nanoTime();
        //创建一个UUID
        String key = UUID.randomUUID().toString();
        //将UUID保存到set中
        retainedKeys.add(key);
        //创建一个弱引用，指向要检测的对象。
        //如果这个弱引用被回收，那么会将reference加入到queue队列中
        //这里之所以创建了一个key，就是为了防止多次启动A页面，然后销毁。无法分清到底是哪一个导致的内存泄漏。现在通过生成的key就可以进行区分了
        final KeyedWeakReference reference = new KeyedWeakReference(watchedReference, key, referenceName, queue);
        //判断reference是否被回收
        ensureGoneAsync(watchStartNanoTime, reference);
    }

    /**
     * LeakCanary will stop watching any references that were passed to {@link #watch(Object, String)}
     * so far.
     */
    public void clearWatchedReferences() {
        retainedKeys.clear();
    }

    boolean isEmpty() {
        removeWeaklyReachableReferences();
        return retainedKeys.isEmpty();
    }

    HeapDump.Builder getHeapDumpBuilder() {
        return heapDumpBuilder;
    }

    Set<String> getRetainedKeys() {
        return new HashSet<>(retainedKeys);
    }

    //判断reference是否被回收
    @SuppressWarnings("ReferenceEquality")
    // Explicitly checking for named null.
    Retryable.Result ensureGone(final KeyedWeakReference reference, final long watchStartNanoTime) {
        long gcStartNanoTime = System.nanoTime();
        long watchDurationMs = NANOSECONDS.toMillis(gcStartNanoTime - watchStartNanoTime);
        //移除已经回收的监控对象
        removeWeaklyReachableReferences();
        //如果当前是debug状态，则直接返回retry
        if (debuggerControl.isDebuggerAttached()) {
            // The debugger can create false leaks.
            return RETRY;
        }
        //监控对象已经回收了，直接返回Done
        if (gone(reference)) {
            return DONE;
        }
        //执行一次垃圾回收
        gcTrigger.runGc();
        //再次移除已经回收的监控对象
        removeWeaklyReachableReferences();
        if (!gone(reference)) {
            //如果仍然没有回收，证明发生了内存泄漏
            long startDumpHeap = System.nanoTime();
            //gc执行的时长
            long gcDurationMs = NANOSECONDS.toMillis(startDumpHeap - gcStartNanoTime);
            //dump出hprof文件
            File heapDumpFile = heapDumper.dumpHeap();
            if (heapDumpFile == RETRY_LATER) {
                // Could not dump the heap.
                //不能生成快照文件的话，进行重试
                return RETRY;
            }
            //生成hprof文件消耗的的时间
            long heapDumpDurationMs = NANOSECONDS.toMillis(System.nanoTime() - startDumpHeap);
            HeapDump heapDump = heapDumpBuilder.heapDumpFile(heapDumpFile).referenceKey(reference.key)
                    .referenceName(reference.name)
                    .watchDurationMs(watchDurationMs)
                    .gcDurationMs(gcDurationMs)
                    .heapDumpDurationMs(heapDumpDurationMs)
                    .build();
            //分析堆内存，heapdumpListener默认是ServiceHeapDumpListener
            heapdumpListener.analyze(heapDump);
        }
        return DONE;
    }

    private void ensureGoneAsync(final long watchStartNanoTime, final KeyedWeakReference reference) {
        watchExecutor.execute(new Retryable() {
            @Override
            public Retryable.Result run() {
                return ensureGone(reference, watchStartNanoTime);
            }
        });
    }

    //判断监控的对象是否已经回收 true:已经回收
    private boolean gone(KeyedWeakReference reference) {
        return !retainedKeys.contains(reference.key);
    }

    private void removeWeaklyReachableReferences() {
        // WeakReferences are enqueued as soon as the object to which they point to becomes weakly
        // reachable. This is before finalization or garbage collection has actually happened.
        KeyedWeakReference ref;
        //循环queue
        while ((ref = (KeyedWeakReference) queue.poll()) != null) {
            //在queue中的ref，说明已经被回收了，所以直接将其对应的key从retainedKeys移除。
            retainedKeys.remove(ref.key);
        }
    }
}
