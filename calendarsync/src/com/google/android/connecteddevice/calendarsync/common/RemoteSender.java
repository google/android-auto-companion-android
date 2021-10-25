package com.google.android.connecteddevice.calendarsync.common;

/** A connection used to send calendar sync messages to any remote device. */
public interface RemoteSender {
  /** Sends a message to the given remote device. */
  void send(String deviceId, byte[] message);
}
