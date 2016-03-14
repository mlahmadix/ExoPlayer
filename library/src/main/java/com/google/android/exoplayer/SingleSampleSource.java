/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.google.android.exoplayer;

import com.google.android.exoplayer.upstream.DataSource;
import com.google.android.exoplayer.upstream.DataSpec;
import com.google.android.exoplayer.upstream.Loader;
import com.google.android.exoplayer.upstream.Loader.Loadable;
import com.google.android.exoplayer.util.Assertions;

import android.net.Uri;
import android.os.SystemClock;

import java.io.IOException;
import java.util.Arrays;

/**
 * A {@link SampleSource} that loads the data at a given {@link Uri} as a single sample.
 */
public final class SingleSampleSource implements SampleSource, TrackStream, Loader.Callback,
    Loadable {

  /**
   * The default minimum number of times to retry loading data prior to failing.
   */
  public static final int DEFAULT_MIN_LOADABLE_RETRY_COUNT = 3;

  /**
   * The initial size of the allocation used to hold the sample data.
   */
  private static final int INITIAL_SAMPLE_SIZE = 1;

  private static final int STATE_SEND_FORMAT = 0;
  private static final int STATE_SEND_SAMPLE = 1;
  private static final int STATE_END_OF_STREAM = 2;

  private final Uri uri;
  private final DataSource dataSource;
  private final Format format;
  private final long durationUs;
  private final int minLoadableRetryCount;
  private final TrackGroupArray tracks;

  private int state;
  private byte[] sampleData;
  private int sampleSize;

  private long pendingResetPositionUs;
  private boolean loadingFinished;
  private Loader loader;
  private IOException currentLoadableException;
  private int currentLoadableExceptionCount;
  private long currentLoadableExceptionTimestamp;

  public SingleSampleSource(Uri uri, DataSource dataSource, Format format, long durationUs) {
    this(uri, dataSource, format, durationUs, DEFAULT_MIN_LOADABLE_RETRY_COUNT);
  }

  public SingleSampleSource(Uri uri, DataSource dataSource, Format format, long durationUs,
      int minLoadableRetryCount) {
    this.uri = uri;
    this.dataSource = dataSource;
    this.format = format;
    this.durationUs = durationUs;
    this.minLoadableRetryCount = minLoadableRetryCount;
    tracks = new TrackGroupArray(new TrackGroup(format));
    sampleData = new byte[INITIAL_SAMPLE_SIZE];
  }

  @Override
  public void maybeThrowError() throws IOException {
    if (currentLoadableException != null && currentLoadableExceptionCount > minLoadableRetryCount) {
      throw currentLoadableException;
    }
  }

  @Override
  public boolean prepare(long positionUs) {
    if (loader == null) {
      loader = new Loader("Loader:" + format.sampleMimeType);
    }
    return true;
  }

  @Override
  public boolean isPrepared() {
    return loader != null;
  }

  @Override
  public long getDurationUs() {
    return durationUs;
  }

  @Override
  public TrackGroupArray getTrackGroups() {
    return tracks;
  }

  @Override
  public TrackStream enable(TrackSelection selection, long positionUs) {
    state = STATE_SEND_FORMAT;
    pendingResetPositionUs = NO_RESET;
    clearCurrentLoadableException();
    maybeStartLoading();
    return this;
  }

  @Override
  public void continueBuffering(long positionUs) {
    maybeStartLoading();
  }

  @Override
  public boolean isReady() {
    return loadingFinished;
  }

  @Override
  public long readReset() {
    long resetPositionUs = pendingResetPositionUs;
    pendingResetPositionUs = NO_RESET;
    return resetPositionUs;
  }

  @Override
  public int readData(FormatHolder formatHolder, SampleHolder sampleHolder) {
    if (state == STATE_END_OF_STREAM) {
      return END_OF_STREAM;
    } else if (state == STATE_SEND_FORMAT) {
      formatHolder.format = format;
      state = STATE_SEND_SAMPLE;
      return FORMAT_READ;
    }

    Assertions.checkState(state == STATE_SEND_SAMPLE);
    if (!loadingFinished) {
      return NOTHING_READ;
    } else {
      sampleHolder.timeUs = 0;
      sampleHolder.size = sampleSize;
      sampleHolder.flags = C.SAMPLE_FLAG_SYNC;
      sampleHolder.ensureSpaceForWrite(sampleHolder.size);
      sampleHolder.data.put(sampleData, 0, sampleSize);
      state = STATE_END_OF_STREAM;
      return SAMPLE_READ;
    }
  }

  @Override
  public void seekToUs(long positionUs) {
    if (state == STATE_END_OF_STREAM) {
      pendingResetPositionUs = positionUs;
      state = STATE_SEND_SAMPLE;
    }
  }

  @Override
  public long getBufferedPositionUs() {
    return state == STATE_END_OF_STREAM || loadingFinished ? C.END_OF_SOURCE_US : 0;
  }

  @Override
  public void disable() {
    state = STATE_END_OF_STREAM;
  }

  @Override
  public void release() {
    if (loader != null) {
      loader.release();
      loader = null;
    }
  }

  // Private methods.

  private void maybeStartLoading() {
    if (loadingFinished || state == STATE_END_OF_STREAM || loader.isLoading()) {
      return;
    }
    if (currentLoadableException != null) {
      long elapsedMillis = SystemClock.elapsedRealtime() - currentLoadableExceptionTimestamp;
      if (elapsedMillis < getRetryDelayMillis(currentLoadableExceptionCount)) {
        return;
      }
      currentLoadableException = null;
    }
    loader.startLoading(this, this);
  }

  private void clearCurrentLoadableException() {
    currentLoadableException = null;
    currentLoadableExceptionCount = 0;
  }

  private long getRetryDelayMillis(long errorCount) {
    return Math.min((errorCount - 1) * 1000, 5000);
  }

  // Loader.Callback implementation.

  @Override
  public void onLoadCompleted(Loadable loadable) {
    loadingFinished = true;
    clearCurrentLoadableException();
  }

  @Override
  public void onLoadCanceled(Loadable loadable) {
    // Never happens.
  }

  @Override
  public void onLoadError(Loadable loadable, IOException e) {
    currentLoadableException = e;
    currentLoadableExceptionCount++;
    currentLoadableExceptionTimestamp = SystemClock.elapsedRealtime();
    maybeStartLoading();
  }

  // Loadable implementation.

  @Override
  public void cancelLoad() {
    // Never happens.
  }

  @Override
  public boolean isLoadCanceled() {
    return false;
  }

  @Override
  public void load() throws IOException, InterruptedException {
    // We always load from the beginning, so reset the sampleSize to 0.
    sampleSize = 0;
    try {
      // Create and open the input.
      dataSource.open(new DataSpec(uri));
      // Load the sample data.
      int result = 0;
      while (result != C.RESULT_END_OF_INPUT) {
        sampleSize += result;
        if (sampleSize == sampleData.length) {
          sampleData = Arrays.copyOf(sampleData, sampleData.length * 2);
        }
        result = dataSource.read(sampleData, sampleSize, sampleData.length - sampleSize);
      }
    } finally {
      dataSource.close();
    }
  }

}
