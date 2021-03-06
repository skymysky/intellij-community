// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.psi.statistics.impl;

import com.intellij.CommonBundle;
import com.intellij.ide.IdeBundle;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManagerEx;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.statistics.StatisticsInfo;
import com.intellij.psi.statistics.StatisticsManager;
import com.intellij.reference.SoftReference;
import com.intellij.util.NotNullFunction;
import com.intellij.util.ScrambledInputStream;
import com.intellij.util.ScrambledOutputStream;
import com.intellij.util.containers.ContainerUtil;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class StatisticsManagerImpl extends StatisticsManager {
  private static final int UNIT_COUNT = 997;
  private static final Object LOCK = new Object();

  private final List<SoftReference<StatisticsUnit>> myUnits = ContainerUtil.newArrayList(Collections.nCopies(UNIT_COUNT, null));
  private final Set<StatisticsUnit> myModifiedUnits = new THashSet<>();
  private boolean myTestingStatistics;

  @Override
  public int getUseCount(@NotNull final StatisticsInfo info) {
    if (info == StatisticsInfo.EMPTY) return 0;

    int useCount = 0;

    for (StatisticsInfo conjunct : info.getConjuncts()) {
      useCount = Math.max(doGetUseCount(conjunct), useCount);
    }

    return useCount;
  }

  private int doGetUseCount(StatisticsInfo info) {
    String key1 = info.getContext();
    int unitNumber = getUnitNumber(key1);
    synchronized (LOCK) {
      StatisticsUnit unit = getUnit(unitNumber);
      return unit.getData(key1, info.getValue());
    }
  }

  @Override
  public int getLastUseRecency(@NotNull StatisticsInfo info) {
    if (info == StatisticsInfo.EMPTY) return 0;

    int recency = Integer.MAX_VALUE;
    for (StatisticsInfo conjunct : info.getConjuncts()) {
      recency = Math.min(doGetRecency(conjunct), recency);
    }
    return recency;
  }

  private int doGetRecency(StatisticsInfo info) {
    String key1 = info.getContext();
    int unitNumber = getUnitNumber(key1);
    synchronized (LOCK) {
      StatisticsUnit unit = getUnit(unitNumber);
      return unit.getRecency(key1, info.getValue());
    }
  }

  @Override
  public void incUseCount(@NotNull final StatisticsInfo info) {
    if (info == StatisticsInfo.EMPTY) return;
    if (ApplicationManager.getApplication().isUnitTestMode() && !myTestingStatistics) {
      return;
    }

    ApplicationManager.getApplication().assertIsDispatchThread();

    for (StatisticsInfo conjunct : info.getConjuncts()) {
      doIncUseCount(conjunct);
    }
  }

  private void doIncUseCount(StatisticsInfo info) {
    final String key1 = info.getContext();
    int unitNumber = getUnitNumber(key1);
    synchronized (LOCK) {
      StatisticsUnit unit = getUnit(unitNumber);
      unit.incData(key1, info.getValue());
      myModifiedUnits.add(unit);
    }
  }

  @Override
  public StatisticsInfo[] getAllValues(final String context) {
    final String[] strings;
    synchronized (LOCK) {
      strings = getUnit(getUnitNumber(context)).getKeys2(context);
    }
    return ContainerUtil.map2Array(strings, StatisticsInfo.class, (NotNullFunction<String, StatisticsInfo>)s -> new StatisticsInfo(context, s));
  }

  @Override
  public void save() {
    synchronized (LOCK) {
      if (!ApplicationManager.getApplication().isUnitTestMode()) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        for (StatisticsUnit unit : myModifiedUnits) {
          saveUnit(unit.getNumber());
        }
      }
      myModifiedUnits.clear();
    }
  }

  @NotNull
  private StatisticsUnit getUnit(int unitNumber) {
    StatisticsUnit unit = SoftReference.dereference(myUnits.get(unitNumber));
    if (unit != null) {
      return unit;
    }

    unit = loadUnit(unitNumber);
    myUnits.set(unitNumber, new SoftReference<>(unit));
    return unit;
  }

  @NotNull
  private static StatisticsUnit loadUnit(int unitNumber) {
    StatisticsUnit unit = new StatisticsUnit(unitNumber);
    if (!ApplicationManager.getApplication().isUnitTestMode()) {
      Path path = getPathToUnit(unitNumber);
      try (InputStream in = new ScrambledInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
        unit.read(in);
      }
      catch (IOException | WrongFormatException ignored) {
      }
    }
    return unit;
  }

  private void saveUnit(int unitNumber) {
    if (!createStoreFolder()) {
      return;
    }

    StatisticsUnit unit = SoftReference.dereference(myUnits.get(unitNumber));
    if (unit == null) {
      return;
    }

    Path path = getPathToUnit(unitNumber);
    try (OutputStream out = new ScrambledOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
      unit.write(out);
    }
    catch (IOException e) {
      Messages.showMessageDialog(
        IdeBundle.message("error.saving.statistics", e.getLocalizedMessage()),
        CommonBundle.getErrorTitle(),
        Messages.getErrorIcon()
      );
    }
  }

  private static int getUnitNumber(String key1) {
    return Math.abs(key1.hashCode() % UNIT_COUNT);
  }

  private static boolean createStoreFolder() {
    Path storeDir = getStoreDir();
    try {
      Files.createDirectories(storeDir);
    }
    catch (IOException e) {
      Logger.getInstance(StatisticsManager.class).error(e);
      Messages.showMessageDialog(
        IdeBundle.message("error.saving.statistic.failed.to.create.folder", storeDir.toString()),
        CommonBundle.getErrorTitle(),
        Messages.getErrorIcon()
      );
      return false;
    }
    return true;
  }

  @SuppressWarnings({"HardCodedStringLiteral"})
  @NotNull
  private static Path getPathToUnit(int unitNumber) {
    return getStoreDir().resolve("unit." + unitNumber);
  }

  private static Path getStoreDir() {
    return PathManagerEx.getAppSystemDir().resolve("stat");
  }

  @TestOnly
  public void enableStatistics(@NotNull Disposable parentDisposable) {
    myTestingStatistics = true;
    Disposer.register(parentDisposable, new Disposable() {
      @Override
      public void dispose() {
        synchronized (LOCK) {
          Collections.fill(myUnits, null);
        }
        myTestingStatistics = false;
      }
    });
  }
}