/*
 * Copyright (c) 2022, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

/*
 * @test IterateMonitorWithDeadObjectTest
 * @summary This locks a monitor, GCs the object, and iterate and perform
 *          various iteration and operations over this monitor.
 * @requires os.family == "linux"
 * @library /testlibrary /test/lib
 * @build IterateMonitorWithDeadObjectTest
 * @run main/native IterateMonitorWithDeadObjectTest
 */

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

public class IterateMonitorWithDeadObjectTest {
    public static native void runTestAndDetachThread();
    public static native void joinTestThread();

    public static void dumpThreadsWithLockedMonitors() {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        threadBean.dumpAllThreads(true, false);
    }

    static {
        System.loadLibrary("IterateMonitorWithDeadObjectTest");
    }

    public static void main(String[] args) throws Exception {
        // Run the part of the test that causes the problematic monitor:
        // - Create an object
        // - Call MonitorEnter on the object to create a monitor
        // - Drop the last reference to the object
        // - GC to clear the weak reference to the object in the monitor
        // - Detach the thread - provoke previous bug
        // - Leave the thread un-joined
        runTestAndDetachThread();

        System.out.println("Dumping threads with locked monitors");

        // After testIt has been called, there's an "owned" monitor with a
        // dead object. The thread dumping code didn't tolerate such a monitor,
        // so run a thread dump and make sure that it doesn't crash/assert.
        dumpThreadsWithLockedMonitors();

        System.out.println("Dumping threads with locked monitors done");

        joinTestThread();
    }
}
