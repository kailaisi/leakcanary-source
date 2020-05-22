/*
 * Copyright (C) 2018 Square, Inc.
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
package com.squareup.leakcanary.internal;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import com.squareup.leakcanary.RefWatcher;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.O;

/**
 * Internal class used to watch for fragments leaks.
 */
public interface FragmentRefWatcher {

    void watchFragments(Activity activity);

    final class Helper {

        private static final String SUPPORT_FRAGMENT_REF_WATCHER_CLASS_NAME =
                "com.squareup.leakcanary.internal.SupportFragmentRefWatcher";

        public static void install(Context context, RefWatcher refWatcher) {
            List<FragmentRefWatcher> fragmentRefWatchers = new ArrayList<>();
            //将实现了FragmentRefWatcher接口的两个实现类加入到fragmentRefWatchers中
            //两个实现类，一个是实现对于V4包下的Fragment的监听，一个是对于当前包下Fragment的监听
            if (SDK_INT >= O) {
                //实现类AndroidOFragmentRefWatcher
                fragmentRefWatchers.add(new AndroidOFragmentRefWatcher(refWatcher));
            }

            try {
                //实现类SupportFragmentRefWatcher用于监听V4包下面的Fragment
                //这里使用反射，是因为SupportFragmentRefWatcher这个类在support-fragment这个module中。
                //所以，如果我们没有引入V4的话，其实这个类是可以不引入的。
                Class<?> fragmentRefWatcherClass = Class.forName(SUPPORT_FRAGMENT_REF_WATCHER_CLASS_NAME);
                Constructor<?> constructor = fragmentRefWatcherClass.getDeclaredConstructor(RefWatcher.class);
                FragmentRefWatcher supportFragmentRefWatcher = (FragmentRefWatcher) constructor.newInstance(refWatcher);
                fragmentRefWatchers.add(supportFragmentRefWatcher);
            } catch (Exception ignored) {
            }


            if (fragmentRefWatchers.size() == 0) {
                return;
            }

            Helper helper = new Helper(fragmentRefWatchers);

            Application application = (Application) context.getApplicationContext();
            application.registerActivityLifecycleCallbacks(helper.activityLifecycleCallbacks);
        }

        private final Application.ActivityLifecycleCallbacks activityLifecycleCallbacks =
                new ActivityLifecycleCallbacksAdapter() {
                    @Override
                    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                        for (FragmentRefWatcher watcher : fragmentRefWatchers) {
                            //这里会调用具体的实现类的watchFragments方法。这里关心的是绑定的Activity的onCreate方法。走到这里的时候已经创建了对应FragmentManager对象
                            //而通过FragmentManager对象可以来registerFragmentLifecycleCallbacks来创建对于其管理的Fragment的生命周期监听
                            watcher.watchFragments(activity);
                        }
                    }
                };

        private final List<FragmentRefWatcher> fragmentRefWatchers;

        private Helper(List<FragmentRefWatcher> fragmentRefWatchers) {
            this.fragmentRefWatchers = fragmentRefWatchers;
        }
    }
}
