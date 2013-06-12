/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.ide.plugins;

import com.intellij.ide.ClassUtilCore;
import com.intellij.ide.IdeBundle;
import com.intellij.idea.Main;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationNamesInfo;
import com.intellij.openapi.components.ComponentConfig;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.ex.WindowManagerEx;
import com.intellij.util.ArrayUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * @author mike
 */
public class PluginManager extends PluginManagerCore {
  @NonNls public static final String INSTALLED_TXT = "installed.txt";

  static final Object lock = new Object();

  public static long startupStart;

  /**
   * Called via reflection
   */
  @SuppressWarnings({"UnusedDeclaration", "HardCodedStringLiteral", "finally"})
  protected static void start(final String mainClass, final String methodName, final String[] args) {
    startupStart = System.nanoTime();

    Main.setFlags(args);

    ThreadGroup threadGroup = new ThreadGroup("Idea Thread Group") {
      @Override
      public void uncaughtException(Thread t, Throwable e) {
        if (e instanceof StartupAbortedException) {
          StartupAbortedException se = (StartupAbortedException)e;
          try {
            if (se.logError()) {
              if (Logger.isInitialized()) {
                getLogger().error(e);
              }
              Main.showMessage("Start Failed", e);
            }
          }
          finally {
            System.exit(se.exitCode());
          }
        }

        if (!(e instanceof ProcessCanceledException)) {
          getLogger().error(e);
        }
      }
    };

    Runnable runnable = new Runnable() {
      @Override
      public void run() {
        try {
          ClassUtilCore.clearJarURLCache();

          Class<?> aClass = Class.forName(mainClass);
          Method method = aClass.getDeclaredMethod(methodName, ArrayUtil.EMPTY_STRING_ARRAY.getClass());
          method.setAccessible(true);
          Object[] argsArray = {args};
          method.invoke(null, argsArray);
        }
        catch (Throwable t) {
          throw new StartupAbortedException(t);
        }
      }
    };

    new Thread(threadGroup, runnable, "Idea Main Thread").start();
  }

  public static void reportPluginError() {
    if (myPluginError != null) {
      String message = IdeBundle.message("title.plugin.error");
      Notifications.Bus.notify(new Notification(message, message, myPluginError, NotificationType.ERROR, new NotificationListener() {
        @SuppressWarnings("AssignmentToStaticFieldFromInstanceMethod")
        @Override
        public void hyperlinkUpdate(@NotNull Notification notification, @NotNull HyperlinkEvent event) {
          notification.expire();

          String description = event.getDescription();
          if (EDIT.equals(description)) {
            PluginManagerConfigurable configurable = new PluginManagerConfigurable(PluginManagerUISettings.getInstance());
            IdeFrame ideFrame = WindowManagerEx.getInstanceEx().findFrameFor(null);
            ShowSettingsUtil.getInstance().editConfigurable((JFrame)ideFrame, configurable);
            return;
          }

          List<String> disabledPlugins = getDisabledPlugins();
          if (myPlugins2Disable != null && DISABLE.equals(description)) {
            for (String pluginId : myPlugins2Disable) {
              if (!disabledPlugins.contains(pluginId)) {
                disabledPlugins.add(pluginId);
              }
            }
          }
          else if (myPlugins2Enable != null && ENABLE.equals(description)) {
            disabledPlugins.removeAll(myPlugins2Enable);
          }

          try {
            saveDisabledPlugins(disabledPlugins, false);
          }
          catch (IOException ignore) { }

          myPlugins2Enable = null;
          myPlugins2Disable = null;
        }
      }));
      myPluginError = null;
    }
  }

  public static boolean isPluginInstalled(PluginId id) {
    return getPlugin(id) != null;
  }

  @Nullable
  public static IdeaPluginDescriptor getPlugin(PluginId id) {
    final IdeaPluginDescriptor[] plugins = getPlugins();
    for (final IdeaPluginDescriptor plugin : plugins) {
      if (Comparing.equal(id, plugin.getPluginId())) {
        return plugin;
      }
    }
    return null;
  }

  public static void handleComponentError(Throwable t, String componentClassName, ComponentConfig config) {
    if (t instanceof StartupAbortedException) {
      throw (StartupAbortedException)t;
    }

    PluginId pluginId = config != null ? config.getPluginId() : getPluginByClassName(componentClassName);

    if (pluginId != null && !CORE_PLUGIN_ID.equals(pluginId.getIdString())) {
      getLogger().warn(t);

      disablePlugin(pluginId.getIdString());

      String message =
        "Plugin '" + pluginId.getIdString() + "' failed to initialize and will be disabled\n" +
        "(reason: " + t.getMessage() + ")\n\n" +
        ApplicationNamesInfo.getInstance().getFullProductName() + " will be restarted.";
      Main.showMessage("Plugin Error", message, false);

      throw new StartupAbortedException(t).exitCode(Main.PLUGIN_ERROR).logError(false);
    }
    else {
      throw new StartupAbortedException("Fatal error initializing '" + componentClassName + "'", t);
    }
  }

  public static class StartupAbortedException extends RuntimeException {
    private int exitCode = Main.STARTUP_EXCEPTION;
    private boolean logError = true;

    public StartupAbortedException(Throwable cause) {
      super(cause);
    }

    public StartupAbortedException(String message, Throwable cause) {
      super(message, cause);
    }

    public int exitCode() {
      return exitCode;
    }

    public StartupAbortedException exitCode(int exitCode) {
      this.exitCode = exitCode;
      return this;
    }

    public boolean logError() {
      return logError;
    }

    public StartupAbortedException logError(boolean logError) {
      this.logError = logError;
      return this;
    }
  }
}
