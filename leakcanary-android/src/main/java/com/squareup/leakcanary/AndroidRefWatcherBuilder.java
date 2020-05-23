package com.squareup.leakcanary;

import android.content.Context;
import android.support.annotation.NonNull;
import com.squareup.leakcanary.internal.DisplayLeakActivity;
import com.squareup.leakcanary.internal.FragmentRefWatcher;
import com.squareup.leakcanary.internal.LeakCanaryInternals;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.squareup.leakcanary.RefWatcher.DISABLED;
import static java.util.concurrent.TimeUnit.SECONDS;

/** A {@link RefWatcherBuilder} with appropriate Android defaults. */
public final class AndroidRefWatcherBuilder extends RefWatcherBuilder<AndroidRefWatcherBuilder> {

  private static final long DEFAULT_WATCH_DELAY_MILLIS = SECONDS.toMillis(5);

  private final Context context;
  private boolean watchActivities = true;
  private boolean watchFragments = true;
  /**
   * 是否允许显示内存泄漏的Activity
   */
  private boolean enableDisplayLeakActivity = false;

  AndroidRefWatcherBuilder(@NonNull Context context) {
    this.context = context.getApplicationContext();
  }

  /**
   * Sets a custom {@link AbstractAnalysisResultService} to listen to analysis results. This
   * overrides any call to {@link #heapDumpListener(HeapDump.Listener)}.
   */
  //设置一个监听者用来监听分析的结果。
  public @NonNull AndroidRefWatcherBuilder listenerServiceClass(@NonNull Class<? extends AbstractAnalysisResultService> listenerServiceClass) {
    enableDisplayLeakActivity = DisplayLeakService.class.isAssignableFrom(listenerServiceClass);
    //设置一个监听者,用来分析泄漏的结果以及发送通知消息
    return heapDumpListener(new ServiceHeapDumpListener(context, listenerServiceClass));
  }

  /**
   * Sets a custom delay for how long the {@link RefWatcher} should wait until it checks if a
   * tracked object has been garbage collected. This overrides any call to {@link
   * #watchExecutor(WatchExecutor)}.
   */
  public @NonNull AndroidRefWatcherBuilder watchDelay(long delay, @NonNull TimeUnit unit) {
    return watchExecutor(new AndroidWatchExecutor(unit.toMillis(delay)));
  }

  /**
   * Whether we should automatically watch activities when calling {@link #buildAndInstall()}.
   * Default is true.
   */
  public @NonNull AndroidRefWatcherBuilder watchActivities(boolean watchActivities) {
    this.watchActivities = watchActivities;
    return this;
  }

  /**
   * Whether we should automatically watch fragments when calling {@link #buildAndInstall()}.
   * Default is true. When true, LeakCanary watches native fragments on Android O+ and support
   * fragments if the leakcanary-support-fragment dependency is in the classpath.
   */
  public @NonNull AndroidRefWatcherBuilder watchFragments(boolean watchFragments) {
    this.watchFragments = watchFragments;
    return this;
  }

  /**
   * Sets the maximum number of heap dumps stored. This overrides any call to
   * {@link LeakCanary#setLeakDirectoryProvider(LeakDirectoryProvider)}
   *
   * @throws IllegalArgumentException if maxStoredHeapDumps < 1.
   */
  public @NonNull AndroidRefWatcherBuilder maxStoredHeapDumps(int maxStoredHeapDumps) {
    LeakDirectoryProvider leakDirectoryProvider =
        new DefaultLeakDirectoryProvider(context, maxStoredHeapDumps);
    LeakCanary.setLeakDirectoryProvider(leakDirectoryProvider);
    return self();
  }

  /**
   * Creates a {@link RefWatcher} instance and makes it available through {@link
   * LeakCanary#installedRefWatcher()}.
   *
   * Also starts watching activity references if {@link #watchActivities(boolean)} was set to true.
   *
   * @throws UnsupportedOperationException if called more than once per Android process.
   */
  //根据对应的设置信息，返回一个RefWatcher对象
  public @NonNull RefWatcher buildAndInstall() {
    if (LeakCanaryInternals.installedRefWatcher != null) {
      throw new UnsupportedOperationException("buildAndInstall() should only be called once.");
    }
    //通过构造者模式中的build()方法创建一个RefWatcher对象,这里面会有很多默认的设置
    RefWatcher refWatcher = build();
    if (refWatcher != DISABLED) {
      //如果允许显示内存泄漏Activity，则进行处理
      if (enableDisplayLeakActivity) {
        LeakCanaryInternals.setEnabledAsync(context, DisplayLeakActivity.class, true);
      }
      //如果设置了监听Activity，那么就为Activity注册生命周期监听
      if (watchActivities) {
        ActivityRefWatcher.install(context, refWatcher);
      }
      //如果设置了监听Fragment，那么就为Fragment注册生命周期监听
      if (watchFragments) {
        FragmentRefWatcher.Helper.install(context, refWatcher);
      }
    }
    LeakCanaryInternals.installedRefWatcher = refWatcher;
    return refWatcher;
  }

  @Override protected boolean isDisabled() {
    return LeakCanary.isInAnalyzerProcess(context);
  }

  @Override protected @NonNull HeapDumper defaultHeapDumper() {
    //内存泄漏的路径
    LeakDirectoryProvider leakDirectoryProvider = LeakCanaryInternals.getLeakDirectoryProvider(context);
    return new AndroidHeapDumper(context, leakDirectoryProvider);
  }

  @Override protected @NonNull DebuggerControl defaultDebuggerControl() {
    return new AndroidDebuggerControl();
  }

  @Override protected @NonNull HeapDump.Listener defaultHeapDumpListener() {
    return new ServiceHeapDumpListener(context, DisplayLeakService.class);
  }

  @Override protected @NonNull ExcludedRefs defaultExcludedRefs() {
    return AndroidExcludedRefs.createAppDefaults().build();
  }

  @Override protected @NonNull WatchExecutor defaultWatchExecutor() {
    return new AndroidWatchExecutor(DEFAULT_WATCH_DELAY_MILLIS);
  }

  @Override protected @NonNull
  List<Class<? extends Reachability.Inspector>> defaultReachabilityInspectorClasses() {
    return AndroidReachabilityInspectors.defaultAndroidInspectors();
  }
}
