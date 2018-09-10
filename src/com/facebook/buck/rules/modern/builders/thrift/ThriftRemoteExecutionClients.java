/*
 * Copyright 2018-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.rules.modern.builders.thrift;

import com.facebook.remoteexecution.cas.ContentAddressableStorage;
import com.facebook.remoteexecution.executionengine.ExecutionEngine;
import com.facebook.thrift.protocol.THeaderProtocol;
import com.facebook.thrift.transport.THeaderTransport;
import com.facebook.thrift.transport.TSocket;
import com.facebook.thrift.transport.TTransport;
import com.facebook.thrift.transport.TTransportException;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.concurrent.GuardedBy;

/** Thrift clients for the Thrift-based remote execution services. */
class ThriftRemoteExecutionClients implements Closeable {

  private static final int SOCKET_TIMEOUT_MILLIS = 1000 * 30; // 30 seconds
  private static final int CONNECTION_TIMEOUT_MILLIS = 1000 * 10; // 10 seconds

  private final String remoteExecutionHost;
  private final int remoteExecutionPort;

  private final String casHost;
  private final int casPort;

  private final Object internalStateLock = new Object();

  @GuardedBy("internalStateLock")
  private final List<TTransport> transportsToClose;

  ThriftRemoteExecutionClients(
      String remoteExecutionEngineHost,
      int remoteExecutionEnginePort,
      String casHost,
      int casPort) {
    this.remoteExecutionHost = remoteExecutionEngineHost;
    this.remoteExecutionPort = remoteExecutionEnginePort;
    this.casHost = casHost;
    this.casPort = casPort;
    transportsToClose = new ArrayList<>();
  }

  @Override
  public void close() throws IOException {
    synchronized (internalStateLock) {
      for (TTransport transport : transportsToClose) {
        transport.close();
      }
    }
  }

  public ContentAddressableStorage.Client createCasClient() throws TTransportException {
    THeaderTransport casTransport = createBlockingTransport(casHost, casPort);
    return new ContentAddressableStorage.Client(new THeaderProtocol(casTransport));
  }

  public ExecutionEngine.Client createExecutionEngineClient() throws TTransportException {
    THeaderTransport remoteExecutionTransport =
        createBlockingTransport(remoteExecutionHost, remoteExecutionPort);
    return new ExecutionEngine.Client(new THeaderProtocol(remoteExecutionTransport));
  }

  private THeaderTransport createBlockingTransport(String host, int port)
      throws TTransportException {
    THeaderTransport transport =
        new THeaderTransport(
            new TSocket(host, port, SOCKET_TIMEOUT_MILLIS, CONNECTION_TIMEOUT_MILLIS));

    transport.setHeader(
        "request_timeout", Integer.toString((int) (CONNECTION_TIMEOUT_MILLIS * 0.8)));

    transport.open();
    synchronized (internalStateLock) {
      transportsToClose.add(transport);
    }

    return transport;
  }
}
