/*
 * Copyright 2011 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.codefollower.yourbase.hbase.engine;

import com.codefollower.yourbase.engine.ConnectionInfo;
import com.codefollower.yourbase.engine.Engine;
import com.codefollower.yourbase.engine.Session;

public class HBaseEngine extends Engine {
    private static final HBaseEngine INSTANCE = new HBaseEngine();

    public static HBaseEngine getInstance() {
        return INSTANCE;
    }

    @Override
    public Session createSession(ConnectionInfo ci) {
        return INSTANCE.createSessionAndValidate(ci);
    }

    @Override
    protected HBaseDatabase createDatabase(ConnectionInfo ci, String cipher) {
        return new HBaseDatabase(ci, cipher);
    }
}
