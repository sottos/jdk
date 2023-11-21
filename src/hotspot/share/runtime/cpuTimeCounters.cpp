/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023 Google LLC. All rights reserved.
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

#include "precompiled.hpp"
#include "runtime/cpuTimeCounters.hpp"
#include "runtime/atomic.hpp"
#include "runtime/os.hpp"

const char* CPUTimeGroups::to_string(CPUTimeType val) {
  switch (val) {
    case CPUTimeType::gc_total:
      return "total_gc_cpu_time";
    case CPUTimeType::gc_parallel_workers:
      return "gc_parallel_workers";
    case CPUTimeType::gc_conc_mark:
      return "gc_conc_mark";
    case CPUTimeType::gc_conc_refine:
      return "gc_conc_refine";
    case CPUTimeType::gc_service:
      return "gc_service";
    case CPUTimeType::vm:
      return "vm";
    case CPUTimeType::conc_dedup:
      return "conc_dedup";
    default:
      ShouldNotReachHere();
  };
}

bool CPUTimeGroups::is_gc_counter(CPUTimeType val) {
  switch (val) {
    case CPUTimeType::gc_parallel_workers:
      return true;
    case CPUTimeType::gc_conc_mark:
      return true;
    case CPUTimeType::gc_conc_refine:
      return true;
    case CPUTimeType::gc_service:
      return true;
    default:
      return false;
  }
  ShouldNotReachHere();
}

CPUTimeCounters* CPUTimeCounters::_instance = nullptr;

CPUTimeCounters::CPUTimeCounters() :
    _cpu_time_counters(),
    _gc_total_cpu_time_diff(0) {
  create_counter(SUN_THREADS, CPUTimeGroups::CPUTimeType::gc_total);
}

void CPUTimeCounters::inc_gc_total_cpu_time(jlong diff) {
  Atomic::add(&_gc_total_cpu_time_diff, diff);
}

void CPUTimeCounters::publish_gc_total_cpu_time() {
  // Ensure that we are only incrementing atomically by using Atomic::cmpxchg
  // to set the value to zero after we obtain the new CPU time difference.
  jlong old_value;
  jlong fetched_value = Atomic::load(&_gc_total_cpu_time_diff);
  jlong new_value = 0;
  do {
    old_value = fetched_value;
    fetched_value = Atomic::cmpxchg(&_gc_total_cpu_time_diff, old_value, new_value);
  } while (old_value != fetched_value);
  get_counter(CPUTimeGroups::CPUTimeType::gc_total)->inc(fetched_value);
}

void CPUTimeCounters::create_counter(CounterNS ns, CPUTimeGroups::CPUTimeType name) {
  if (UsePerfData) {
    EXCEPTION_MARK;
    if (os::is_thread_cpu_time_supported()) {
      _cpu_time_counters[static_cast<int>(name)] =
                  PerfDataManager::create_counter(ns, CPUTimeGroups::to_string(name),
                                                  PerfData::U_Ticks, CHECK);
    }
  }
}

void CPUTimeCounters::create_counter(CPUTimeGroups::CPUTimeType group) {
  create_counter(SUN_THREADS_CPUTIME, group);
}

PerfCounter* CPUTimeCounters::get_counter(CPUTimeGroups::CPUTimeType name) {
  return _cpu_time_counters[static_cast<int>(name)];
}

ThreadTotalCPUTimeClosure::~ThreadTotalCPUTimeClosure() {
    CPUTimeCounters* instance = CPUTimeCounters::get_instance();
    PerfCounter* counter = instance->get_counter(_name);
    jlong net_cpu_time = _gc_total - counter->get_value();
    counter->inc(net_cpu_time);
    if (CPUTimeGroups::is_gc_counter(_name)) {
      instance->inc_gc_total_cpu_time(net_cpu_time);
    }
}

void ThreadTotalCPUTimeClosure::do_thread(Thread* thread) {
    // The default code path (fast_thread_cpu_time()) asserts that
    // pthread_getcpuclockid() and clock_gettime() must return 0. Thus caller
    // must ensure the thread exists and has not terminated.
    assert(os::is_thread_cpu_time_supported(), "os must support cpu time");
    _gc_total += os::thread_cpu_time(thread);
}


