/*
 * Copyright (c) 2019-2022. FbUtilities Contributors and Frederic Bregier
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 *  under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 *   OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.fb.utils.guid;

import org.fb.utils.various.RandomUtil;
import org.fb.utils.various.SysErrLogger;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import static org.fb.utils.various.SystemPropertyUtil.*;

public final class JvmProcessId {
  /**
   * 2 bytes value maximum
   */
  static final int JVMPID;
  /**
   * Definition for Machine Id replacing MAC address
   */
  private static final Pattern MACHINE_ID_PATTERN =
      Pattern.compile("^(?:[0-9a-fA-F][:-]?){6,8}$");
  private static final int MACHINE_ID_LEN = 6;
  /**
   * MAX value on 3 bytes (64 system use 2^22 id)
   */
  private static final int MAX_PID = 0xFFFFFF;
  private static final Object[] EMPTY_OBJECTS = new Object[0];
  private static final Class<?>[] EMPTY_CLASSES = new Class<?>[0];
  private static final Pattern COMPILE = Pattern.compile("[:-]");

  /**
   * Try to get Mac Address but could be also changed dynamically
   */
  static byte[] mac;
  static int macInt;
  static byte jvmId;

  static {
    JVMPID = jvmProcessId();
    mac = macAddress();
    macInt = macAddressAsInt();
    jvmId = jvmInstanceId();
  }

  private JvmProcessId() {
  }

  /**
   * Use both PID and MAC address but as 8 bytes hash
   *
   * @return one id as much as possible unique
   */
  public static byte jvmInstanceId() {
    final long id = 31L * jvmProcessId() + macAddressAsInt();
    return (byte) (Long.hashCode(id) & 0xFF);
  }

  /**
   * @return the JVM Process ID
   */
  public static int jvmProcessId() {
    // Note: may fail in some JVM implementations
    // something like '<pid>@<hostname>', at least in SUN / Oracle JVMs
    try {
      final ClassLoader loader = getSystemClassLoader();
      String value;
      value =
          jvmProcessIdManagementFactory(loader, EMPTY_OBJECTS, EMPTY_CLASSES);
      final int atIndex = value.indexOf('@');
      if (atIndex >= 0) {
        value = value.substring(0, atIndex);
      }
      int processId = -1;
      processId = parseProcessId(processId, value);
      if (processId < 0 || processId > MAX_PID) {
        processId = RandomUtil.RANDOM.nextInt(MAX_PID + 1);
      }
      return processId;
    } catch (final Throwable e) {//NOSONAR
      SysErrLogger.FAKE_LOGGER.syserr(e);
      return RandomUtil.RANDOM.nextInt(MAX_PID + 1);
    }
  }

  /**
   * @return MAC address as int (truncated to 4 bytes instead of 6)
   */
  public static int macAddressAsInt() {
    return (mac[3] & 0xFF) << 24 | (mac[2] & 0xFF) << 16 |
           (mac[1] & 0xFF) << 8 | mac[0] & 0xFF;
  }

  private static ClassLoader getSystemClassLoader() {
    return ClassLoader.getSystemClassLoader();
  }

  /**
   * @return the processId as String
   */
  private static String jvmProcessIdManagementFactory(final ClassLoader loader,
                                                      final Object[] emptyObjects,
                                                      final Class<?>[] emptyClasses) {
    String value;
    try {
      // Invoke
      // java.lang.management.ManagementFactory.getRuntimeMXBean().getName()
      final Class<?> mgmtFactoryType =
          Class.forName("java.lang.management.ManagementFactory", true, loader);
      final Class<?> runtimeMxBeanType =
          Class.forName("java.lang.management.RuntimeMXBean", true, loader);

      final Method getRuntimeMXBean =
          mgmtFactoryType.getMethod("getRuntimeMXBean", emptyClasses);
      final Object bean = getRuntimeMXBean.invoke(null, emptyObjects);
      final Method getName =
          runtimeMxBeanType.getDeclaredMethod("getName", emptyClasses);
      value = (String) getName.invoke(bean, emptyObjects);
    } catch (final Exception e) {
      SysErrLogger.FAKE_LOGGER.syserr(
          "Unable to get PID, try another way: " + e.getMessage());

      try {
        // Invoke android.os.Process.myPid()
        final Class<?> processType =
            Class.forName("android.os.Process", true, loader);
        final Method myPid = processType.getMethod("myPid", emptyClasses);
        value = myPid.invoke(null, emptyObjects).toString();
      } catch (final Exception e2) {
        SysErrLogger.FAKE_LOGGER.syserr(
            "Unable to get PID: " + e2.getMessage());
        value = "";
      }
    }
    return value;
  }

  /**
   * @return the processId
   */
  private static int parseProcessId(final int oldProcessId,
                                    final String customProcessId) {
    int processId = oldProcessId;
    try {
      processId = Integer.parseInt(customProcessId);
    } catch (final NumberFormatException e) {
      // Malformed input.
    }
    if (processId < 0 || processId > MAX_PID) {
      processId = -1;
    }
    return processId;
  }

  /**
   * @return the mac address if possible, else random values
   */
  public static byte[] macAddress() {
    try {
      byte[] machineId = null;
      final String customMachineId = getMachineId();
      if (customMachineId != null &&
          MACHINE_ID_PATTERN.matcher(customMachineId).matches()) {
        machineId = parseMachineId(customMachineId);
      }

      if (machineId == null) {
        machineId = defaultMachineId();
      }
      return machineId;
    } catch (final Throwable e) {//NOSONAR
      return RandomUtil.getRandom(MACHINE_ID_LEN);
    }
  }

  private static byte[] parseMachineId(String value) {
    // Strip separators.
    value = COMPILE.matcher(value).replaceAll("");

    final byte[] machineId = new byte[MACHINE_ID_LEN];
    for (int i = 0; i < value.length() && i < MACHINE_ID_LEN; i += 2) {
      machineId[i] = (byte) Integer.parseInt(value.substring(i, i + 2), 16);
    }

    return machineId;
  }

  private static byte[] defaultMachineId() {
    // Find the best MAC address available.
    final byte[] notFound = { -1 };
    byte[] bestMacAddr = notFound;
    InetAddress bestInetAddr;
    try {
      bestInetAddr = InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 });
    } catch (final UnknownHostException e) {
      // Never happens.
      throw new IllegalArgumentException(e);
    }

    // Retrieve the list of available network interfaces.
    final Map<NetworkInterface, InetAddress> ifaces = new LinkedHashMap<>();
    try {
      for (final Enumeration<NetworkInterface> i =
           NetworkInterface.getNetworkInterfaces(); i.hasMoreElements(); ) {
        final NetworkInterface iface = i.nextElement();
        // Use the interface with proper INET addresses only.
        final Enumeration<InetAddress> addrs = iface.getInetAddresses();
        if (addrs.hasMoreElements()) {
          final InetAddress a = addrs.nextElement();
          if (!a.isLoopbackAddress()) {
            ifaces.put(iface, a);
          }
        }
      }
    } catch (final SocketException ignored) {
      // nothing
    }

    for (final Entry<NetworkInterface, InetAddress> entry : ifaces.entrySet()) {
      final NetworkInterface iface = entry.getKey();
      final InetAddress inetAddr = entry.getValue();
      if (iface.isVirtual()) {
        continue;
      }

      final byte[] macAddr;
      try {
        macAddr = iface.getHardwareAddress();
      } catch (final SocketException e) {
        continue;
      }

      boolean replace = false;
      int res = compareAddresses(bestMacAddr, macAddr);
      if (res < 0) {
        // Found a better MAC address.
        replace = true;
      } else if (res == 0) {
        // Two MAC addresses are of pretty much same quality.
        res = compareAddresses(bestInetAddr, inetAddr);
        if (res < 0 || res == 0 && bestMacAddr.length < macAddr.length) {
          // Found a MAC address with better INET address.
          // Cannot tell the difference. Choose the longer one.
          replace = true;
        }
      }

      if (replace) {
        bestMacAddr = macAddr;
        bestInetAddr = inetAddr;
      }
    }

    if (bestMacAddr == notFound) {
      bestMacAddr = RandomUtil.getRandom(MACHINE_ID_LEN);
    }
    return bestMacAddr;
  }

  /**
   * @return positive - current is better, 0 - cannot tell from MAC addr,
   *     negative - candidate is better.
   */
  private static int compareAddresses(final byte[] current,
                                      final byte[] candidate) {
    if (candidate == null) {
      return 1;
    }
    // Must be EUI-48 or longer.
    if (candidate.length < 6) {
      return 1;
    }
    // Must not be filled with only 0 and 1.
    boolean onlyZeroAndOne = true;
    for (final byte b : candidate) {
      if (b != 0 && b != 1) {
        onlyZeroAndOne = false;
        break;
      }
    }
    if (onlyZeroAndOne) {
      return 1;
    }
    // Must not be a multicast address
    if ((candidate[0] & 1) != 0) {
      return 1;
    }
    // Prefer globally unique address.
    if ((current[0] & 2) == 0) {
      if ((candidate[0] & 2) == 0) {
        // Both current and candidate are globally unique addresses.
        return 0;
      } else {
        // Only current is globally unique.
        return 1;
      }
    } else {
      if ((candidate[0] & 2) == 0) {
        // Only candidate is globally unique.
        return -1;
      } else {
        // Both current and candidate are non-unique.
        return 0;
      }
    }
  }

  /**
   * @return positive - current is better, 0 - cannot tell, negative -
   *     candidate
   *     is better
   */
  private static int compareAddresses(final InetAddress current,
                                      final InetAddress candidate) {
    return scoreAddress(current) - scoreAddress(candidate);
  }

  private static int scoreAddress(final InetAddress addr) {
    if (addr.isAnyLocalAddress()) {
      return 0;
    }
    if (addr.isMulticastAddress()) {
      return 1;
    }
    if (addr.isLinkLocalAddress()) {
      return 2;
    }
    if (addr.isSiteLocalAddress()) {
      return 3;
    }

    return 4;
  }

  /**
   * Up to the 6 first bytes will be used. If Null or less than 6 bytes, extra
   * bytes will be randomly generated.
   *
   * @param mac the MAC address in byte format (up to the 6 first
   *     bytes will
   *     be used)
   */
  public static synchronized void setMac(final byte[] mac) {
    if (mac == null) {
      JvmProcessId.mac = RandomUtil.getRandom(MACHINE_ID_LEN);
    } else {
      JvmProcessId.mac = Arrays.copyOf(mac, MACHINE_ID_LEN);
      for (int i = mac.length; i < MACHINE_ID_LEN; i++) {
        JvmProcessId.mac[i] = (byte) RandomUtil.RANDOM.nextInt(256);
      }
    }
    macInt = macAddressAsInt();
  }
}