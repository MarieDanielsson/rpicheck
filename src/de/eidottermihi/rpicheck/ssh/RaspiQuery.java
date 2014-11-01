package de.eidottermihi.rpicheck.ssh;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.schmizz.sshj.AndroidConfig;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.connection.ConnectionException;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Session.Command;
import net.schmizz.sshj.transport.TransportException;
import net.schmizz.sshj.userauth.UserAuthException;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.xfer.FileSystemFile;
import net.schmizz.sshj.xfer.scp.SCPFileTransfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;

import de.eidottermihi.rpicheck.beans.DiskUsageBean;
import de.eidottermihi.rpicheck.beans.NetworkInterfaceInformation;
import de.eidottermihi.rpicheck.beans.ProcessBean;
import de.eidottermihi.rpicheck.beans.RaspiMemoryBean;
import de.eidottermihi.rpicheck.beans.UptimeBean;
import de.eidottermihi.rpicheck.beans.VcgencmdBean;
import de.eidottermihi.rpicheck.beans.WlanBean;

/**
 * Simple API for querying a Raspberry Pi computer.
 * 
 * @author Michael
 * @version <ul>
 *          <li>0.4.0 added sending commands</li>
 *          <li>0.3.3 added private/public key authentification</li>
 *          <li>0.3.2 fixed wlan parsing</li>
 *          <li>0.3.1 added halt commando, general bugfixing of path problems</li>
 *          <li>0.3.0 added mechanism to find vcgencmd path, refactored ip
 *          adress query to support other interfaces than eth0, added wlan
 *          status query</li>
 *          <li>0.2.1 added full path to vcgencmd (not in PATH in some
 *          distributions)</li>
 *          <li>0.2.0 reboot command</li>
 *          <li>0.1.5 beans serializable</li>
 *          <li>0.1.4 added process query</li>
 *          <li>0.1.3 minor changes (avg load as %, show data unit)</li>
 *          <li>0.1.2 added distribution name query</li>
 *          <li>0.1.1 added ip adress and disk usage query, changed UptimeBean
 *          avgLoad to String</li>
 *          <li>0.1.0 first alpha</li>
 *          </ul>
 * 
 */
public class RaspiQuery implements IRaspiQuery {

	private static final Logger LOGGER = LoggerFactory
			.getLogger(RaspiQuery.class);

	public static final int FREQ_ARM = 0;
	public static final int FREQ_CORE = 1;

	private static final Pattern IPADDRESS_PATTERN = Pattern
			.compile("\\b(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b");
	private static final Pattern CPU_PATTERN = Pattern.compile("[0-9.]{4,}");
	private static final String UPTIME_CMD = "cat /proc/uptime";
	private static final String CPU_SERIAL_CMD = "cat /proc/cpuinfo | grep Serial";
	private static final String MEMORY_INFO_CMD = "free | egrep 'Mem' | sed 's/[[:space:]]\\+/,/g'";
	private static final String DISK_USAGE_CMD = "df -h";
	private static final String DF_COMMAND_HEADER_START = "Filesystem";
	private static final String DISTRIBUTION_CMD = "cat /etc/*-release | grep PRETTY_NAME";
	private static final String PROCESS_NO_ROOT_CMD = "ps -U root -u root -N";
	private static final String PROCESS_ALL = "ps -A";
	private static final String N_A = "n/a";

	private static final int DEFAULT_SSH_PORT = 22;

	private SSHClient client;
	private String hostname;
	private String username;
	private int port = DEFAULT_SSH_PORT;

	/**
	 * Initialize a new RaspiQuery.
	 * 
	 * @param host
	 *            hostname or ip adress of a running raspberry pi
	 * @param user
	 *            username for ssh login
	 * @param pass
	 *            password for ssh login
	 * @param port
	 *            ssh port to use (if null, default will be used)
	 */
	public RaspiQuery(final String host, final String user, final Integer port) {
		if (Strings.isNullOrEmpty(host)) {
			throw new IllegalArgumentException("hostname should not be blank.");
		} else if (Strings.isNullOrEmpty(user)) {
			throw new IllegalArgumentException("username should not be blank.");
		} else {
			LOGGER.info("New RaspiQuery for host: {}", host);
			this.hostname = host;
			this.username = user;
			if (port != null) {
				this.port = port;
			}
		}
	}

	/**
	 * Queries the current CPU temperature.
	 * 
	 * @param vcgencmdPath
	 *            the path to vcgencmd
	 * @return the temperature in Celsius
	 * @throws RaspiQueryException
	 *             if something goes wrong
	 */
	private final Double queryCpuTemp(String vcgencmdPath)
			throws RaspiQueryException {
		if (client != null) {
			if (client.isConnected() && client.isAuthenticated()) {
				Session session;
				try {
					session = client.startSession();
					final String cmdString = vcgencmdPath + " measure_temp";
					final Command cmd = session.exec(cmdString);
					cmd.join(30, TimeUnit.SECONDS);
					String output = IOUtils.readFully(cmd.getInputStream())
							.toString();
					return this.parseTemperature(output);
				} catch (IOException e) {
					throw RaspiQueryException.createTransportFailure(hostname,
							e);
				}
			} else {
				throw new IllegalStateException(
						"You must establish a connection first.");
			}
		} else {
			throw new IllegalStateException(
					"You must establish a connection first.");
		}
	}

	/**
	 * Queries the current cpu frequency.
	 * 
	 * @param unit
	 *            cpu or arm
	 * @param vcgencmdPath
	 *            the path of the vcgendcmd tool
	 * @return the frequency in hz
	 * @throws RaspiQueryException
	 *             if something goes wrong
	 */
	private final long queryFreq(final int unit, final String vcgencmdPath)
			throws RaspiQueryException {
		if (client != null) {
			if (client.isConnected() && client.isAuthenticated()) {
				Session session;
				try {
					session = client.startSession();
					String cmdString = vcgencmdPath + " measure_clock";
					if (unit == FREQ_ARM) {
						cmdString += " arm";
					} else if (unit == FREQ_CORE) {
						cmdString += " core";
					} else {
						return 0;
					}
					final Command cmd = session.exec(cmdString);
					cmd.join(30, TimeUnit.SECONDS);
					String output = IOUtils.readFully(cmd.getInputStream())
							.toString();
					return this.parseFrequency(output);
				} catch (IOException e) {
					throw RaspiQueryException.createTransportFailure(hostname,
							e);
				}
			} else {
				throw new IllegalStateException(
						"You must establish a connection first.");
			}
		} else {
			throw new IllegalStateException(
					"You must establish a connection first.");
		}
	}

	/**
	 * Queries information available via vcgencmd (temperature, frequencies,
	 * firmware version, ...).
	 * 
	 * @return a {@link VcgencmdBean} with the data
	 * @throws RaspiQueryException
	 *             when vcgencmd was not found on the machine
	 */
	public final VcgencmdBean queryVcgencmd() throws RaspiQueryException {
		LOGGER.debug("Querying vcgencmd...");
		// first, find the location of vcgencmd
		final String vcgencmdPath = findVcgencmd();
		if (vcgencmdPath == null) {
			throw RaspiQueryException.createVcgencmdNotFound();
		}
		// create Bean
		final VcgencmdBean bean = new VcgencmdBean();
		bean.setArmFrequency(this.queryFreq(FREQ_ARM, vcgencmdPath));
		bean.setCoreFrequency(this.queryFreq(FREQ_CORE, vcgencmdPath));
		bean.setCoreVolts(this.queryVolts(vcgencmdPath));
		bean.setCpuTemperature(this.queryCpuTemp(vcgencmdPath));
		bean.setVersion(this.queryFirmwareVersion(vcgencmdPath));
		return bean;
	}

	/**
	 * Queries the firmware version.
	 * 
	 * @param vcgencmdPath
	 *            path to vcgencmd
	 * @return the firmware Version
	 * @throws RaspiQueryException
	 *             if something goes wrong
	 */
	private String queryFirmwareVersion(String vcgencmdPath)
			throws RaspiQueryException {
		LOGGER.debug("Querying firmware version...");
		if (client != null) {
			if (client.isConnected() && client.isAuthenticated()) {
				Session session;
				try {
					session = client.startSession();
					String cmdString = vcgencmdPath + " version";
					final Command cmd = session.exec(cmdString);
					cmd.join(30, TimeUnit.SECONDS);
					String output = IOUtils.readFully(cmd.getInputStream())
							.toString();
					final String result = this.parseFirmwareVersion(output);
					LOGGER.debug("Firmware version: {}", result);
					return result;
				} catch (IOException e) {
					throw RaspiQueryException.createTransportFailure(hostname,
							e);
				}
			} else {
				throw new IllegalStateException(
						"You must establish a connection first.");
			}
		} else {
			throw new IllegalStateException(
					"You must establish a connection first.");
		}
	}

	/**
	 * Parses the output of "vcgendcmd version".
	 * 
	 * @param output
	 *            the output
	 * @return the version string
	 */
	private String parseFirmwareVersion(final String output) {
		final String[] splitted = output.split("\n");
		if (splitted.length >= 3) {
			if (splitted[2].startsWith("version ")) {
				return splitted[2].replace("version ", "");
			} else {
				return splitted[2];
			}
		} else {
			LOGGER.error("Could not parse firmware. Maybe the output of 'vcgencmd version' changed.");
			LOGGER.debug("Output of 'vcgencmd version': \n{}", output);
			return N_A;
		}
	}

	/**
	 * Checks the known paths to the vcgencmd executable and return the first
	 * path found.
	 * 
	 * @return the path or <code>null</code>, when vcgencmd was not found
	 * @throws RaspiQueryException
	 *             if something goes wrong
	 */
	private String findVcgencmd() throws RaspiQueryException {
		if (client != null) {
			if (client.isConnected() && client.isAuthenticated()) {
				try {
					String foundPath = null;
					// 1. check if vcgencmd is in PATH
					String pathGuess = "vcgencmd";
					if (checkPath(pathGuess, client)) {
						foundPath = pathGuess;
					}
					// 2. check if vcgencmd is in /usr/bin
					pathGuess = "/usr/bin/vcgencmd";
					if (foundPath == null && checkPath(pathGuess, client)) {
						foundPath = pathGuess;
					}
					// 3. check if vcgencmd is in /opt/vc/bin
					pathGuess = "/opt/vc/bin/vcgencmd";
					if (foundPath == null && checkPath(pathGuess, client)) {
						foundPath = pathGuess;
					}
					if (foundPath != null) {
						LOGGER.info("Found vcgencmd in path: {}.", foundPath);
						return foundPath;
					} else {
						LOGGER.error("vcgencmd was not found. Verify that vcgencmd is available in /usr/bin or /opt/vc/bin.");
						return null;
					}
				} catch (IOException e) {
					throw RaspiQueryException.createTransportFailure(hostname,
							e);
				}
			} else {
				throw new IllegalStateException(
						"You must establish a connection first.");
			}
		} else {
			throw new IllegalStateException(
					"You must establish a connection first.");
		}
	}

	/**
	 * Checks if the path is a correct path to vcgencmd.
	 * 
	 * @param path
	 *            the path to check
	 * @param client
	 *            authenticated and open client
	 * @return true, if correct, false if not
	 * @throws IOException
	 *             if something ssh related goes wrong
	 */
	private boolean checkPath(String path, SSHClient client) throws IOException {
		final Session session = client.startSession();
		session.allocateDefaultPTY();
		LOGGER.debug("Checking vcgencmd location: {}", path);
		String cmdString = path;
		final Command cmd = session.exec(cmdString);
		cmd.join(30, TimeUnit.SECONDS);
		session.close();
		String output = IOUtils.readFully(cmd.getInputStream()).toString();
		LOGGER.debug("Path check output: {}", output);
		if (output.contains("not found")
				|| output.contains("No such file or directory")) {
			return false;
		} else {
			// vcgencmd was found
			return true;
		}
	}

	/**
	 * Queries network information.
	 * 
	 * @return a List with informations to every interface found (loopback
	 *         excluded).
	 * @throws RaspiQueryException
	 *             if something goes wrong.
	 */
	public List<NetworkInterfaceInformation> queryNetworkInformation()
			throws RaspiQueryException {
		List<NetworkInterfaceInformation> interfacesInfo = new ArrayList<NetworkInterfaceInformation>();
		// 1. find all network interfaces (excluding loopback interface) and
		// check carrier
		List<String> interfaces = this.queryInterfaceList();
		LOGGER.info("Available interfaces: {}", interfaces);
		for (String interfaceName : interfaces) {
			NetworkInterfaceInformation interfaceInfo = new NetworkInterfaceInformation();
			interfaceInfo.setName(interfaceName);
			// check carrier
			interfaceInfo.setHasCarrier(this.checkCarrier(interfaceName));
			interfacesInfo.add(interfaceInfo);
		}
		List<NetworkInterfaceInformation> wirelessInterfaces = new ArrayList<NetworkInterfaceInformation>();
		// 2. for every interface with carrier check ip adress
		for (NetworkInterfaceInformation interfaceBean : interfacesInfo) {
			if (interfaceBean.isHasCarrier()) {
				interfaceBean.setIpAdress(this.queryIpAddress(interfaceBean
						.getName()));
				// check if interface is wireless (interface name starts with
				// "wlan")
				if (interfaceBean.getName().startsWith("wlan")) {
					// add to wireless interfaces list
					wirelessInterfaces.add(interfaceBean);
				}
			}
		}
		// 3. query signal level and link status of wireless interfaces
		// NetworkInterfaceInformation wlan0Mock = new
		// NetworkInterfaceInformation();
		// wlan0Mock.setHasCarrier(true);
		// wlan0Mock.setName("wlan0");
		// wirelessInterfaces.add(wlan0Mock);
		if (wirelessInterfaces.size() > 0) {
			this.queryWlanInfo(wirelessInterfaces);
		}
		return interfacesInfo;
	}

	/**
	 * Queries the link level and signal quality of the wireless interfaces via
	 * "cat /proc/net/wireless".
	 * 
	 * @param wirelessInterfaces
	 *            a List with wireless interfaces
	 * @throws RaspiQueryException
	 *             if something goes wrong
	 */
	private void queryWlanInfo(
			List<NetworkInterfaceInformation> wirelessInterfaces)
			throws RaspiQueryException {
		LOGGER.info("Querying wireless interfaces...");
		if (client != null) {
			if (client.isConnected() && client.isAuthenticated()) {
				Session session;
				try {
					session = client.startSession();
					final String cmdString = "cat /proc/net/wireless";
					final Command cmd = session.exec(cmdString);
					cmd.join(30, TimeUnit.SECONDS);
					String output = IOUtils.readFully(cmd.getInputStream())
							.toString();
					LOGGER.debug("Real output of /proc/net/wireless: \n{}",
							output);
					// appending mock wlan0 line
					// StringBuilder sb = new StringBuilder(output);
					// sb.append("wlan0: 0000 100. 95. 0. 0 0 0 0 0 0\n");
					// output = sb.toString();
					this.parseWlan(output, wirelessInterfaces);
				} catch (IOException e) {
					throw RaspiQueryException.createTransportFailure(hostname,
							e);
				}
			} else {
				throw new IllegalStateException(
						"You must establish a connection first.");
			}
		} else {
			throw new IllegalStateException(
					"You must establish a connection first.");
		}
	}

	private void parseWlan(String output,
			List<NetworkInterfaceInformation> wirelessInterfaces) {
		final String[] lines = output.split("\n");
		for (String line : lines) {
			if (line.startsWith("Inter-") || line.startsWith(" face")) {
				LOGGER.debug("Skipping header line: {}", line);
				continue;
			}
			final String[] cols = line.split("\\s+");
			if (cols.length >= 11) {
				LOGGER.debug("Parsing output line: {}", line);
				// getting interface name
				final String name = cols[1].replace(":", "");
				LOGGER.debug("Parsed interface name: {}", name);
				final String linkQuality = cols[3].replace(".", "");
				LOGGER.debug("LINK QUALITY>>>{}<<<", linkQuality);
				final String linkLevel = cols[4].replace(".", "");
				LOGGER.debug("LINK LEVEL>>>{}<<<", linkLevel);
				Integer linkQualityInt = null;
				try {
					linkQualityInt = Integer.parseInt(linkQuality);
				} catch (NumberFormatException e) {
					LOGGER.warn(
							"Could not parse link quality field for input: {}.",
							linkQuality);
				}
				Integer signalLevelInt = null;
				try {
					signalLevelInt = Integer.parseInt(linkLevel);
				} catch (Exception e) {
					LOGGER.warn(
							"Could not parse link level field for input: {}.",
							linkLevel);
				}
				LOGGER.debug(
						"WLAN status of {}: link quality {}, signal level {}.",
						new Object[] { name, linkQualityInt, signalLevelInt });
				for (NetworkInterfaceInformation iface : wirelessInterfaces) {
					if (iface.getName().equals(name)) {
						final WlanBean wlanInfo = new WlanBean();
						wlanInfo.setLinkQuality(linkQualityInt);
						wlanInfo.setSignalLevel(signalLevelInt);
						LOGGER.debug(
								"Adding wifi-status info to interface {}.",
								iface.getName());
						iface.setWlanInfo(wlanInfo);
					}
				}
			}
		}

	}

	/**
	 * Queries the ip address of a network interface.
	 * 
	 * @param name
	 *            the interface name (eth0, wlan0, ...).
	 * @return the IP address
	 * @throws RaspiQueryException
	 */
	private String queryIpAddress(String name) throws RaspiQueryException {
		LOGGER.info("Querying ip address of {}...", name);
		if (client != null) {
			if (client.isConnected() && client.isAuthenticated()) {
				Session session;
				try {
					session = client.startSession();
					final String cmdString = "ip -f inet addr show dev " + name
							+ " | sed -n 2p";
					final Command cmd = session.exec(cmdString);
					cmd.join(30, TimeUnit.SECONDS);
					final String output = IOUtils.readFully(
							cmd.getInputStream()).toString();
					LOGGER.debug("Output of ssh query: {}.", output);
					final Matcher m = IPADDRESS_PATTERN.matcher(output);
					if (m.find()) {
						final String ipAddress = m.group().trim();
						LOGGER.info("{} - IP address: {}.", name, ipAddress);
						return ipAddress;
					} else {
						LOGGER.error(
								"IP address pattern: No match found for output: {}.",
								output);
						return null;
					}
				} catch (IOException e) {
					throw RaspiQueryException.createTransportFailure(hostname,
							e);
				}
			} else {
				throw new IllegalStateException(
						"You must establish a connection first.");
			}
		} else {
			throw new IllegalStateException(
					"You must establish a connection first.");
		}
	}

	/**
	 * Checks if the specified interface has a carrier up and running via
	 * "cat /sys/class/net/[interface]/carrier".
	 * 
	 * @param interfaceName
	 *            the interface to check
	 * @return true, when the interface has a carrier up and running
	 * @throws RaspiQueryException
	 *             if something goes wrong
	 */
	private boolean checkCarrier(String interfaceName)
			throws RaspiQueryException {
		LOGGER.info("Checking carrier of {}...", interfaceName);
		if (client != null) {
			if (client.isConnected() && client.isAuthenticated()) {
				Session session;
				try {
					session = client.startSession();
					final String cmdString = "cat /sys/class/net/"
							+ interfaceName + "/carrier";
					final Command cmd = session.exec(cmdString);
					cmd.join(30, TimeUnit.SECONDS);
					final String output = IOUtils.readFully(
							cmd.getInputStream()).toString();
					if (output.contains("1")) {
						LOGGER.debug("{} has a carrier up and running.",
								interfaceName);
						return true;
					} else {
						LOGGER.debug("{} has no carrier.", interfaceName);
						return false;
					}
				} catch (IOException e) {
					throw RaspiQueryException.createTransportFailure(hostname,
							e);
				}
			} else {
				throw new IllegalStateException(
						"You must establish a connection first.");
			}
		} else {
			throw new IllegalStateException(
					"You must establish a connection first.");
		}
	}

	/**
	 * Queries which interfaces are available via "/sys/class/net". Loopback
	 * interfaces are excluded.
	 * 
	 * @return a List with all interface names (eth0, wlan0,...).
	 * @throws RaspiQueryException
	 *             if something goes wrong
	 */
	private List<String> queryInterfaceList() throws RaspiQueryException {
		LOGGER.info("Querying network interfaces...");
		if (client != null) {
			if (client.isConnected() && client.isAuthenticated()) {
				Session session;
				try {
					session = client.startSession();
					final String cmdString = "ls -1 /sys/class/net";
					final Command cmd = session.exec(cmdString);
					cmd.join(30, TimeUnit.SECONDS);
					final String output = IOUtils.readFully(
							cmd.getInputStream()).toString();
					final String[] lines = output.split("\n");
					final List<String> interfaces = new ArrayList<String>();
					for (String interfaceLine : lines) {
						if (!interfaceLine.startsWith("lo")) {
							LOGGER.debug("Found interface {}.", interfaceLine);
							interfaces.add(interfaceLine);
						}
					}
					return interfaces;
				} catch (IOException e) {
					throw RaspiQueryException.createTransportFailure(hostname,
							e);
				}
			} else {
				throw new IllegalStateException(
						"You must establish a connection first.");
			}
		} else {
			throw new IllegalStateException(
					"You must establish a connection first.");
		}
	}

	public final Double queryVolts(String vcgencmdPath)
			throws RaspiQueryException {
		LOGGER.info("Querying core volts...");
		if (client != null) {
			if (client.isConnected() && client.isAuthenticated()) {
				Session session;
				try {
					session = client.startSession();
					final String cmdString = vcgencmdPath
							+ " measure_volts core";
					final Command cmd = session.exec(cmdString);
					cmd.join(30, TimeUnit.SECONDS);
					final String output = IOUtils.readFully(
							cmd.getInputStream()).toString();
					return this.formatVolts(output);
				} catch (IOException e) {
					throw RaspiQueryException.createTransportFailure(hostname,
							e);
				}
			} else {
				throw new IllegalStateException(
						"You must establish a connection first.");
			}
		} else {
			throw new IllegalStateException(
					"You must establish a connection first.");
		}
	}

	// TODO Idee: Kommandos verpacken in
	// "echo START_CMD_1; CMD; echo ENDE_CMD_1" und alles in einem exec-Channel
	// senden
	public final String twoCommandsInOne() throws RaspiQueryException {
		if (client != null) {
			if (client.isConnected() && client.isAuthenticated()) {
				Session session;
				StringBuilder sb = new StringBuilder();
				try {
					session = client.startSession();
					final String cmdString = "echo '--LS start--';echo '--LS end--';ls -l;echo '--LS end'; pwd";
					Command cmd = session.exec(cmdString);
					cmd.join(30, TimeUnit.SECONDS);
					final String output = IOUtils.readFully(
							cmd.getInputStream()).toString();
					sb.append(output);
					cmd.close();
					session.close();
					return sb.toString();
				} catch (IOException e) {
					throw RaspiQueryException.createTransportFailure(hostname,
							e);
				}
			} else {
				throw new IllegalStateException(
						"You must establish a connection first.");
			}
		} else {
			throw new IllegalStateException(
					"You must establish a connection first.");
		}
	}

	/**
	 * Queries uptime and average load.
	 * 
	 * @return a {@link UptimeBean}
	 * @throws RaspiQueryException
	 *             if something goes wrong
	 */
	public final UptimeBean queryUptime() throws RaspiQueryException {
		LOGGER.info("Querying uptime...");
		if (client != null) {
			if (client.isConnected() && client.isAuthenticated()) {
				Session session;
				try {
					session = client.startSession();
					final Command cmd = session.exec(UPTIME_CMD);
					cmd.join(30, TimeUnit.SECONDS);
					final String output = IOUtils.readFully(
							cmd.getInputStream()).toString();
					return this.formatUptime(output);
				} catch (IOException e) {
					throw RaspiQueryException.createTransportFailure(hostname,
							e);
				}
			} else {
				throw new IllegalStateException(
						"You must establish a connection first.");
			}
		} else {
			throw new IllegalStateException(
					"You must establish a connection first.");
		}
	}

	/**
	 * Queries the cpu serial.
	 * 
	 * @return the cpu serial
	 * @throws RaspiQueryException
	 *             if something goes wrong
	 */
	public final String queryCpuSerial() throws RaspiQueryException {
		LOGGER.info("Querying serial number...");
		if (client != null) {
			if (client.isConnected() && client.isAuthenticated()) {
				Session session;
				try {
					session = client.startSession();
					final Command cmd = session.exec(CPU_SERIAL_CMD);
					cmd.join(30, TimeUnit.SECONDS);
					String output = IOUtils.readFully(cmd.getInputStream())
							.toString();
					return this.formatCpuSerial(output);
				} catch (IOException e) {
					throw RaspiQueryException.createTransportFailure(hostname,
							e);
				}
			} else {
				throw new IllegalStateException(
						"You must establish a connection first.");
			}
		} else {
			throw new IllegalStateException(
					"You must establish a connection first.");
		}
	}

	/**
	 * Query memory information.
	 * 
	 * @return a {@link RaspiMemoryBean}
	 * @throws RaspiQueryException
	 *             if something goes wrong
	 */
	public final RaspiMemoryBean queryMemoryInformation()
			throws RaspiQueryException {
		LOGGER.info("Querying memory information...");
		if (client != null) {
			if (client.isConnected() && client.isAuthenticated()) {
				Session session;
				try {
					session = client.startSession();
					final Command cmd = session.exec(MEMORY_INFO_CMD);
					cmd.join(30, TimeUnit.SECONDS);
					return this.formatMemoryInfo(IOUtils.readFully(
							cmd.getInputStream()).toString());
				} catch (IOException e) {
					throw RaspiQueryException.createTransportFailure(hostname,
							e);
				}
			} else {
				throw new IllegalStateException(
						"You must establish a connection first.");
			}
		} else {
			throw new IllegalStateException(
					"You must establish a connection first.");
		}
	}

	/**
	 * Query the disk usage of the raspberry pi.
	 * 
	 * @return a List with information for every disk
	 * @throws RaspiQueryException
	 *             if something goes wrong
	 */
	public final List<DiskUsageBean> queryDiskUsage()
			throws RaspiQueryException {
		LOGGER.info("Querying disk usage...");
		if (client != null) {
			if (client.isConnected() && client.isAuthenticated()) {
				Session session;
				try {
					session = client.startSession();
					final Command cmd = session.exec(DISK_USAGE_CMD);
					cmd.join(30, TimeUnit.SECONDS);
					return this.parseDiskUsage(IOUtils
							.readFully(cmd.getInputStream()).toString().trim());
				} catch (IOException e) {
					throw RaspiQueryException.createTransportFailure(hostname,
							e);
				}
			} else {
				throw new IllegalStateException(
						"You must establish a connection first.");
			}
		} else {
			throw new IllegalStateException(
					"You must establish a connection first.");
		}
	}

	/**
	 * Queries the distribution name.
	 * 
	 * @return the distribution name
	 * @throws RaspiQueryException
	 *             if something goes wrong
	 */
	public final String queryDistributionName() throws RaspiQueryException {
		LOGGER.info("Querying distribution name...");
		if (client != null) {
			if (client.isConnected() && client.isAuthenticated()) {
				Session session;
				try {
					session = client.startSession();
					final Command cmd = session.exec(DISTRIBUTION_CMD);
					cmd.join(30, TimeUnit.SECONDS);
					return this.parseDistribution(IOUtils
							.readFully(cmd.getInputStream()).toString().trim());
				} catch (IOException e) {
					throw RaspiQueryException.createTransportFailure(hostname,
							e);
				}
			} else {
				throw new IllegalStateException(
						"You must establish a connection first.");
			}
		} else {
			throw new IllegalStateException(
					"You must establish a connection first.");
		}
	}

	/**
	 * Queries the running processes.
	 * 
	 * @param showRootProcesses
	 *            if processes of root should be shown
	 * @return List with running processes
	 * @throws RaspiQueryException
	 *             if something goes wrong
	 */
	public final List<ProcessBean> queryProcesses(boolean showRootProcesses)
			throws RaspiQueryException {
		LOGGER.info("Querying running processes...");
		if (client != null) {
			if (client.isConnected() && client.isAuthenticated()) {
				Session session;
				try {
					session = client.startSession();
					final Command cmd = session
							.exec(showRootProcesses ? PROCESS_ALL
									: PROCESS_NO_ROOT_CMD);
					cmd.join(30, TimeUnit.SECONDS);
					return this.parseProcesses(IOUtils
							.readFully(cmd.getInputStream()).toString().trim());
				} catch (IOException e) {
					throw RaspiQueryException.createTransportFailure(hostname,
							e);
				}
			} else {
				throw new IllegalStateException(
						"You must establish a connection first.");
			}
		} else {
			throw new IllegalStateException(
					"You must establish a connection first.");
		}
	}

	/**
	 * Inits a reboot via command 'sudo /sbin/shutdown -r now'. For shutdown,
	 * root privileges are required.
	 * 
	 * @param sudoPassword
	 *            the sudo password
	 * @throws RaspiQueryException
	 */
	public final void sendRebootSignal(String sudoPassword)
			throws RaspiQueryException {
		if (sudoPassword == null) {
			LOGGER.info("No sudo password for reboot specified. Using empty password instead.");
			sudoPassword = "";
		}
		final StringBuilder sb = new StringBuilder();
		if (client != null) {
			if (client.isConnected() && client.isAuthenticated()) {
				Session session;
				try {
					session = client.startSession();
					session.allocateDefaultPTY();
					final String command = sb.append("echo ").append("\"")
							.append(sudoPassword).append("\"")
							.append(" | sudo -S /sbin/shutdown -r now")
							.toString();
					final String rebootCmdLogger = "echo \"??SUDO_PW??\" | sudo -S /sbin/shutdown -r now";
					LOGGER.info("Sending reboot command: {}", rebootCmdLogger);
					Command cmd = session.exec(command);
					cmd.join();
					session.close();
					if (cmd.getExitStatus() != null && cmd.getExitStatus() != 0) {
						LOGGER.warn("Sudo unknown: Trying \"reboot\"...");
						// openelec running
						session = client.startSession();
						session.allocateDefaultPTY();
						session.exec("reboot");
						try {
							session.join(250, TimeUnit.MILLISECONDS);
							LOGGER.debug("join successful after 'reboot'.");
						} catch (ConnectionException e) {
							// system went down
							LOGGER.debug("'reboot' successful!");
						}
					}
				} catch (IOException e) {
					throw RaspiQueryException.createTransportFailure(hostname,
							e);
				}
			} else {
				throw new IllegalStateException(
						"You must establish a connection first.");
			}
		} else {
			throw new IllegalStateException(
					"You must establish a connection first.");
		}
	}

	/**
	 * Inits a system halt via command 'sudo /sbin/shutdown -h now'. For halt,
	 * root privileges are required.
	 * 
	 * @param sudoPassword
	 *            the sudo password
	 * @throws RaspiQueryException
	 */
	public final void sendHaltSignal(String sudoPassword)
			throws RaspiQueryException {
		if (sudoPassword == null) {
			LOGGER.info("No sudo password for halt specified. Using empty password instead.");
			sudoPassword = "";
		}
		final StringBuilder sb = new StringBuilder();
		if (client != null) {
			if (client.isConnected() && client.isAuthenticated()) {
				Session session;
				try {
					session = client.startSession();
					session.allocateDefaultPTY();
					final String command = sb.append("echo ").append("\"")
							.append(sudoPassword).append("\"")
							.append(" | sudo -S /sbin/shutdown -h now")
							.toString();
					final String haltCmdLogger = "echo \"??SUDO_PW??\" | sudo -S /sbin/shutdown -h now";
					LOGGER.info("Sending halt command: {}", haltCmdLogger);
					Command cmd = session.exec(command);
					cmd.join();
					session.close();
					if (cmd.getExitStatus() != null && cmd.getExitStatus() != 0) {
						// openelec running
						session = client.startSession();
						session.allocateDefaultPTY();
						LOGGER.warn("Sudo unknown: Trying \"halt\"...");
						session.exec("halt");
						try {
							session.join(250, TimeUnit.MILLISECONDS);
							LOGGER.debug("'halt' probably didnt work.");
						} catch (ConnectionException e) {
							// system went down
							LOGGER.debug("'halt' successful!");
						}
					}
				} catch (IOException e) {
					throw RaspiQueryException.createTransportFailure(hostname,
							e);
				}
			} else {
				throw new IllegalStateException(
						"You must establish a connection first.");
			}
		} else {
			throw new IllegalStateException(
					"You must establish a connection first.");
		}
	}

	/**
	 * Parses the output of the ps command.
	 * 
	 * @param output
	 * @return List with processes
	 */
	private List<ProcessBean> parseProcesses(String output) {
		final List<String> lines = Splitter.on("\n").trimResults()
				.splitToList(output);
		final List<ProcessBean> processes = new LinkedList<ProcessBean>();
		int count = 0;
		for (String line : lines) {
			if (count == 0) {
				// first line
				count++;
				continue;
			}
			// split line at whitespaces
			final List<String> cols = Splitter.on(CharMatcher.WHITESPACE)
					.omitEmptyStrings().trimResults().splitToList(line);
			if (cols.size() >= 4) {
				try {
					// command may contain whitespace, so join again
					final StringBuilder sb = new StringBuilder();
					for (int i = 3; i < cols.size(); i++) {
						sb.append(cols.get(i)).append(' ');
					}
					processes.add(new ProcessBean(
							Integer.parseInt(cols.get(0)), cols.get(1), cols
									.get(2), sb.toString()));
				} catch (NumberFormatException e) {
					LOGGER.error("Could not parse processes.");
					LOGGER.error("Error occured on following line: {}", line);
				}
			} else {
				LOGGER.error("Line[] length: {}", cols.size());
				LOGGER.error(
						"Expcected another output of ps. Skipping line: {}",
						line);
			}
		}
		return processes;
	}

	private String parseDistribution(String output) {
		final String[] split = output.trim().split("=");
		if (split.length >= 2) {
			final String distriWithApostroph = split[1];
			final String distri = distriWithApostroph.replace("\"", "");
			return distri;
		} else {
			LOGGER.error("Could not parse distribution. Make sure 'cat /etc/*-release' works on your distribution.");
			LOGGER.debug("Output of {}: \n{}", DISTRIBUTION_CMD, output);
			return N_A;
		}
	}

	/**
	 * Parses the output of the disk usage commando.
	 * 
	 * @param output
	 *            the output
	 * @return a List with {@link DiskUsageBean}
	 */
	private List<DiskUsageBean> parseDiskUsage(String output) {
		final String[] lines = output.split("\n");
		final List<DiskUsageBean> disks = new LinkedList<DiskUsageBean>();
		for (String line : lines) {
			if (line.startsWith(DF_COMMAND_HEADER_START)) {
				continue;
			}
			// split string at whitespaces
			final String[] linesSplitted = line.split("\\s+");
			if (linesSplitted.length >= 6) {
				if (linesSplitted.length > 6) {
					// whitespace in mountpoint path
					StringBuilder sb = new StringBuilder();
					for (int i = 5; i < linesSplitted.length; i++) {
						sb.append(linesSplitted[i]);
						if (i != linesSplitted.length - 1) {
							sb.append(" ");
						}
					}
					disks.add(new DiskUsageBean(linesSplitted[0],
							linesSplitted[1], linesSplitted[2],
							linesSplitted[3], linesSplitted[4], sb.toString()));
				} else {
					disks.add(new DiskUsageBean(linesSplitted[0],
							linesSplitted[1], linesSplitted[2],
							linesSplitted[3], linesSplitted[4],
							linesSplitted[5]));
				}
			} else {
				LOGGER.warn(
						"Expected another output of df -h. Skipping line: {}",
						line);
			}
		}
		return disks;
	}

	private RaspiMemoryBean formatMemoryInfo(String output) {
		final String[] split = output.split(",");
		if (split.length >= 3) {
			final long total = Long.parseLong(split[1]);
			final long used = Long.parseLong(split[2]);
			return new RaspiMemoryBean(total, used);
		} else {
			LOGGER.error("Expected a different output of command: {}",
					MEMORY_INFO_CMD);
			LOGGER.debug("Output was : {}", output);
			return new RaspiMemoryBean(
					"Memory information could not be queried. See the log for details.");
		}
	}

	private String formatCpuSerial(String output) {
		final String[] split = output.trim().split(":");
		if (split.length >= 2) {
			final String cpuSerial = split[1].trim();
			return cpuSerial;
		} else {
			LOGGER.error(
					"Could not query cpu serial number. Expected another output of '{}'.",
					CPU_SERIAL_CMD);
			LOGGER.debug("Output of '{}': \n{}", CPU_SERIAL_CMD, output);
			return N_A;
		}
	}

	private UptimeBean formatUptime(String output) {
		final String[] split = output.split(" ");
		if (split.length >= 2) {
			double uptimeFull = Double.parseDouble(split[0]);
			double uptimeIdle = Double.parseDouble(split[1]);
			return new UptimeBean(uptimeFull, uptimeIdle);
		} else {
			LOGGER.error("Expected a different output of command: {}",
					UPTIME_CMD);
			LOGGER.debug("Actual output was: {}", output);
			return new UptimeBean(
					"Uptime and system load could not be queried. See the log for details.");
		}
	}

	private Double formatVolts(String output) {
		final String[] splitted = output.trim().split("=");
		if (splitted.length >= 2) {
			final String voltsWithUnit = splitted[1];
			final String volts = voltsWithUnit.substring(0,
					voltsWithUnit.length() - 1);
			return Double.parseDouble(volts);
		} else {
			LOGGER.error("Could not parse cpu voltage.");
			LOGGER.debug("Output of 'vcgencmd measure_volts core': \n{}",
					output);
			return 0D;
		}
	}

	/**
	 * Formats the output of the vcgencmd measure_temp command.
	 * 
	 * @param output
	 *            output of vcgencmd
	 * @return formatted temperature string
	 */
	private Double parseTemperature(final String output) {
		final Matcher m = CPU_PATTERN.matcher(output);
		if (m.find()) {
			final String formatted = m.group().trim();
			double temperature = Double.parseDouble(formatted);
			return temperature;
		} else {
			LOGGER.error("Could not parse cpu temperature.");
			LOGGER.debug("Output of 'vcgencmd measure_temp': \n{}", output);
			return 0D;
		}
	}

	/**
	 * Parse the output of the command "vcgencmd measure_clock [core/arm]".
	 * 
	 * @param output
	 * @return the frequency in Hz
	 */
	private long parseFrequency(final String output) {
		final String[] splitted = output.trim().split("=");
		long formatted = 0;
		if (splitted.length >= 2) {
			try {
				formatted = Long.parseLong(splitted[1]);
			} catch (NumberFormatException e) {
				LOGGER.error("Could not parse frequency.");
				LOGGER.debug(
						"Output of 'vcgencmd measure_clock [core/arm]': \n{}",
						output);
			}
		} else {
			LOGGER.error("Could not parse frequency.");
			LOGGER.debug("Output of 'vcgencmd measure_clock [core/arm]': \n{}",
					output);
		}
		return formatted;
	}

	// /**
	// * Establishes a ssh connection to a raspberry pi via ssh.
	// *
	// * @param password
	// * the ssh password
	// * @throws RaspiQueryException
	// * - if connection, authentication or transport fails
	// */
	// public final void connect(String password) throws RaspiQueryException {
	// LOGGER.info("Connecting to host: {} on port {}.", hostname, port);
	// client = new SSHClient(new AndroidConfig());
	// LOGGER.info("Using no host key verification.");
	// client.addHostKeyVerifier(new NoHostKeyVerifierImplementation());
	// try {
	// client.connect(hostname, port);
	// } catch (IOException e) {
	// throw RaspiQueryException
	// .createConnectionFailure(hostname, port, e);
	// }
	// try {
	// client.authPassword(username, password);
	// } catch (UserAuthException e) {
	// throw RaspiQueryException.createAuthenticationFailure(hostname,
	// username, e);
	// } catch (TransportException e) {
	// throw RaspiQueryException.createTransportFailure(hostname, e);
	// }
	// }

	/**
	 * Establishes a ssh connection with public key authentification.
	 * 
	 * @param keyfilePath
	 *            path to the private key file in PKCS11/OpenSSH format
	 * @throws RaspiQueryException
	 */
	public final void connectWithPubKeyAuth(final String keyfilePath)
			throws RaspiQueryException {
		LOGGER.info("Connecting to host: {} on port {}.", hostname, port);
		client = new SSHClient(new AndroidConfig());
		LOGGER.info("Using no host key verification.");
		client.addHostKeyVerifier(new NoHostKeyVerifierImplementation());
		LOGGER.info("Using private/public key authentification.");
		try {
			client.connect(hostname, port);
		} catch (IOException e) {
			throw RaspiQueryException
					.createConnectionFailure(hostname, port, e);
		}
		try {
			final KeyProvider keyProvider = client.loadKeys(keyfilePath);
			client.authPublickey(username, keyProvider);
		} catch (UserAuthException e) {
			LOGGER.info("Authentification failed.", e);
			throw RaspiQueryException.createAuthenticationFailure(hostname,
					username, e);
		} catch (TransportException e) {
			throw RaspiQueryException.createTransportFailure(hostname, e);
		} catch (IOException e) {
			throw RaspiQueryException.createIOException(e);
		}
	}

	/**
	 * Establishes a ssh connection with public key authentification.
	 * 
	 * @param path
	 *            path to the private key file in PKCS11/OpenSSH format
	 * @throws RaspiQueryException
	 */
	public void connectWithPubKeyAuthAndPassphrase(String path,
			String privateKeyPass) throws RaspiQueryException {
		LOGGER.info("Connecting to host: {} on port {}.", hostname, port);
		client = new SSHClient(new AndroidConfig());
		LOGGER.info("Using no host key verification.");
		client.addHostKeyVerifier(new NoHostKeyVerifierImplementation());
		LOGGER.info("Using private/public key authentification with passphrase.");
		try {
			client.connect(hostname, port);
		} catch (IOException e) {
			throw RaspiQueryException
					.createConnectionFailure(hostname, port, e);
		}
		try {
			final KeyProvider keyProvider = client.loadKeys(path,
					privateKeyPass.toCharArray());
			client.authPublickey(username, keyProvider);
		} catch (UserAuthException e) {
			LOGGER.info("Authentification failed.", e);
			throw RaspiQueryException.createAuthenticationFailure(hostname,
					username, e);
		} catch (TransportException e) {
			throw RaspiQueryException.createTransportFailure(hostname, e);
		} catch (IOException e) {
			throw RaspiQueryException.createIOException(e);
		}
	}

	/**
	 * Disconnects the current client.
	 * 
	 * @throws RaspiQueryException
	 *             if something goes wrong
	 * 
	 * @throws IOException
	 *             if something goes wrong
	 */
	public final void disconnect() throws RaspiQueryException {
		if (client != null) {
			if (client.isConnected()) {
				try {
					LOGGER.debug("Disconnecting from host {}.",
							this.getHostname());
					client.disconnect();
				} catch (IOException e) {
					throw RaspiQueryException.createIOException(e);
				}
			}
		}
	}

	public final String getFile(String path) {
		if (client != null) {
			if (client.isConnected()) {
				SCPFileTransfer scpClient = new SCPFileTransfer(client);
				try {
					scpClient.download("/boot/config.txt", new FileSystemFile(
							"/"));
				} catch (IOException e) {
					e.printStackTrace();
				}

			}
		}
		return null;
	}

	public String getHostname() {
		return hostname;
	}

	public void setHostname(String hostname) {
		this.hostname = hostname;
	}

	/**
	 * Runs the specified command.
	 * 
	 * @param command
	 *            the command to run
	 * @throws RaspiQueryException
	 *             when something goes wrong
	 */
	public String run(String command) throws RaspiQueryException {
		LOGGER.info("Running custom command: {}", command);
		if (client != null) {
			if (client.isConnected() && client.isAuthenticated()) {
				Session session;
				try {
					session = client.startSession();
					session.allocateDefaultPTY();
					final Command cmd = session.exec(command);
					cmd.join(20, TimeUnit.SECONDS);
					cmd.close();
					final String output = IOUtils.readFully(
							cmd.getInputStream()).toString();
					final String error = IOUtils
							.readFully(cmd.getErrorStream()).toString();
					final StringBuilder sb = new StringBuilder();
					final String out = sb.append(output).append(error)
							.toString();
					LOGGER.debug("Output of '{}': {}", command, out);
					session.close();
					return out;
				} catch (IOException e) {
					throw RaspiQueryException.createTransportFailure(hostname,
							e);
				}
			} else {
				throw new IllegalStateException(
						"You must establish a connection first.");
			}
		} else {
			throw new IllegalStateException(
					"You must establish a connection first.");
		}

	}

	@Override
	public SSHClient connect(String host, int port) throws RaspiQueryException {
		SSHClient client = new SSHClient(new AndroidConfig());
		client.addHostKeyVerifier(new NoHostKeyVerifierImplementation());
		try {
			client.connect(host, port);
			return client;
		} catch (IOException e) {
			throw RaspiQueryException.createConnectionFailure(host, port, e);
		}
	}

	@Override
	public SSHClient connect(String host) throws RaspiQueryException {
		return connect(host, DEFAULT_SSH_PORT);
	}

	@Override
	public SSHClient auth(SSHClient client, String user, String password)
			throws RaspiQueryException {
		if (!client.isConnected()) {
			return null;
		}
		try {
			client.authPassword(user, password);
			return client;
		} catch (UserAuthException e) {
			throw RaspiQueryException.createAuthenticationFailure(
					client.getRemoteHostname(), user, e);
		} catch (TransportException e) {
			throw RaspiQueryException.createTransportFailure(
					client.getRemoteHostname(), e);
		}
	}

	@Override
	public SSHClient authKeys(SSHClient client, String user, String privateKey)
			throws RaspiQueryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public SSHClient authKeys(SSHClient client, String user, String privateKey,
			String password) throws RaspiQueryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Session startSession(SSHClient client) throws RaspiQueryException {
		if (client.isConnected() && client.isAuthenticated()) {
			try {
				return client.startSession();
			} catch (ConnectionException e) {
				throw RaspiQueryException.createTransportFailure(
						client.getRemoteHostname(), e);
			} catch (TransportException e) {
				throw RaspiQueryException.createTransportFailure(
						client.getRemoteHostname(), e);
			}
		}
		return null;
	}

	@Override
	public List<QueryResult> query(Session session, List<Query> queries)
			throws RaspiQueryException {
		// build command
		StringBuilder sb = new StringBuilder();
		for (Query query : queries) {
			sb.append("echo 'RASPI_START_").append(query.getId()).append(">';");
			sb.append(query.getCommand()).append(";");
			sb.append("echo '<RASPI_END_").append(query.getId()).append("';");
		}
		try {
			System.out.println(sb.toString());
			Command commandAll = session.exec(sb.toString());
			commandAll.join(30, TimeUnit.SECONDS);
			String commandAllOutput = IOUtils.readFully(
					commandAll.getInputStream()).toString();
			System.out.println(commandAllOutput);
			for (Query query : queries) {
				String start = "RASPI_START_" + query.getId() + ">";
				String ende = "<RASPI_END_" + query.getId();
				int indexOfStart = commandAllOutput.indexOf(start);
				int indexOfEnde = commandAllOutput.indexOf(ende);
				if (indexOfStart != -1 && indexOfEnde != -1) {
					int startCmd = indexOfStart + start.length();
					int endCmd = indexOfEnde - 1;
					String output = commandAllOutput
							.substring(startCmd, endCmd);
					System.out.println(output);
				}
			}
		} catch (ConnectionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (TransportException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
	}
}
