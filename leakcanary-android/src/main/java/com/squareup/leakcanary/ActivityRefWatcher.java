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

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.support.annotation.NonNull;

import com.squareup.leakcanary.internal.ActivityLifecycleCallbacksAdapter;

/**
 * @deprecated This was initially part of the LeakCanary API, but should not be any more.
 * {@link AndroidRefWatcherBuilder#watchActivities} should be used instead.
 * We will make this class internal in the next major version.
 */
//用于当Activity执行了onDestroy以后，将引用加入到监控队列中
@SuppressWarnings("DeprecatedIsStillUsed")
@Deprecated
public final class ActivityRefWatcher {

    public static void installOnIcsPlus(@NonNull Application application,
                                        @NonNull RefWatcher refWatcher) {
        install(application, refWatcher);
    }

    public static void install(@NonNull Context context, @NonNull RefWatcher refWatcher) {
        Application application = (Application) context.getApplicationContext();
        //创建一个对于Activity的弱引用监听类
        ActivityRefWatcher activityRefWatcher = new ActivityRefWatcher(application, refWatcher);
        //对传入的应用的Application注册一个对于Activity的生命周期监听函数
        application.registerActivityLifecycleCallbacks(activityRefWatcher.lifecycleCallbacks);
    }

    private final Application.ActivityLifecycleCallbacks lifecycleCallbacks =
            new ActivityLifecycleCallbacksAdapter() {
                //只监听destory方法，将调用destory的activity添加到监听watcher中
                @Override
                public void onActivityDestroyed(Activity activity) {
                    refWatcher.watch(activity);
                }
            };

    private final Application application;
    private final RefWatcher refWatcher;

    private ActivityRefWatcher(Application application, RefWatcher refWatcher) {
        this.application = application;
        this.refWatcher = refWatcher;
    }

    public void watchActivities() {
        // Make sure you don't get installed twice.
        //把以前注册的监听器都移除掉，否则会进行两次回调的处理
        stopWatchingActivities();
        application.registerActivityLifecycleCallbacks(lifecycleCallbacks);
    }

    public void stopWatchingActivities() {
        application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks);
    }
}
