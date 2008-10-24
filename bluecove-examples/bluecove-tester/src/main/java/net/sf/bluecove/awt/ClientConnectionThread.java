/**
 *  BlueCove - Java library for Bluetooth
 *  Copyright (C) 2006-2007 Vlad Skarzhevskyy
 * 
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 *  @version $Id$
 */
package net.sf.bluecove.awt;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.bluetooth.L2CAPConnection;
import javax.microedition.io.Connection;
import javax.microedition.io.Connector;
import javax.microedition.io.StreamConnection;

import net.sf.bluecove.Configuration;
import net.sf.bluecove.ConnectionHolder;
import net.sf.bluecove.ConnectionHolderL2CAP;
import net.sf.bluecove.ConnectionHolderStream;
import net.sf.bluecove.Logger;
import net.sf.bluecove.util.BluetoothTypesInfo;
import net.sf.bluecove.util.IOUtils;
import net.sf.bluecove.util.StringUtils;
import net.sf.bluecove.util.TimeUtils;

/**
 * @author vlads
 * 
 */
public class ClientConnectionThread extends Thread {

	private Object threadLocalBluetoothStack;

	private static int connectionCount = 0;

	private String serverURL;

	private ConnectionHolder c;

	private boolean stoped = false;

	boolean isRunning = false;

	boolean isConnecting = false;

	long receivedCount = 0;

	long reportedSize = 0;

	long receivedPacketsCount = 0;

	boolean rfcomm;

	public static final int interpretDataChars = 0;

	public static final int interpretDataStats = 1;

	int interpretData = interpretDataChars;

	long reported = 0;

	private StringBuffer dataBuf = new StringBuffer();

	private boolean binaryData = false;

	private FileOutputStream fileOut;

	ClientConnectionThread(String serverURL) {
		super("ClientConnectionThread" + (++connectionCount));
		this.serverURL = serverURL;
		threadLocalBluetoothStack = Configuration.threadLocalBluetoothStack;
	}

	public void run() {
		try {
			rfcomm = BluetoothTypesInfo.isRFCOMM(serverURL);
			if (!rfcomm && !BluetoothTypesInfo.isL2CAP(serverURL)) {
				Logger.error("unsupported connection type " + serverURL);
				return;
			}
			Configuration.cldcStub.setThreadLocalBluetoothStack(threadLocalBluetoothStack);
			Connection conn = null;
			try {
				isConnecting = true;
				Logger.debug("Connecting:" + serverURL + " ...");
				conn = Connector.open(serverURL);
			} catch (IOException e) {
				Logger.error("Connection error", e);
				return;
			} finally {
				isConnecting = false;
			}
			if (rfcomm) {
				ConnectionHolderStream cs = new ConnectionHolderStream((StreamConnection) conn);
				c = cs;
				cs.is = cs.conn.openInputStream();
				cs.os = cs.conn.openOutputStream();
				isRunning = true;
				while (!stoped) {
					int data = cs.is.read();
					if (data == -1) {
						Logger.debug("EOF recived");
						break;
					}
					receivedCount++;
					printdataReceivedRFCOMM(data);
				}
				if (dataBuf.length() > 0) {
					Logger.debug("cc:" + StringUtils.toBinaryText(dataBuf));
				}
			} else { // l2cap
				ConnectionHolderL2CAP lc = new ConnectionHolderL2CAP((L2CAPConnection) conn);
				isRunning = true;
				c = lc;
				while (!stoped) {
					while ((!lc.channel.ready()) && (!stoped)) {
						Thread.sleep(100);
					}
					if (stoped) {
						break;
					}
					int receiveMTU = lc.channel.getReceiveMTU();
					byte[] data = new byte[receiveMTU];
					int length = lc.channel.receive(data);
					receivedCount += length;
					receivedPacketsCount++;
					printdataReceivedL2CAP(data, length);
				}
			}
		} catch (IOException e) {
			if (!stoped) {
				Logger.error("Communication error", e);
			}
		} catch (Throwable e) {
			Logger.error("Error", e);
		} finally {
			isRunning = false;
			if (c != null) {
				c.shutdown();
			}
			closeFile();
		}
	}

	private void printdataReceivedRFCOMM(int data) {
		switch (interpretData) {
		case interpretDataChars:
			char c = (char) data;
			if ((!binaryData) && (c < ' ')) {
				binaryData = true;
			}
			dataBuf.append(c);
			if (((!binaryData) && (c == '\n')) || (dataBuf.length() > 32)) {
				Logger.debug("cc:" + StringUtils.toBinaryText(dataBuf));
				dataBuf = new StringBuffer();
			}
			break;
		case interpretDataStats:
			long now = System.currentTimeMillis();
			if (now - reported > 5 * 1000) {
				int size = (int) (receivedCount - reportedSize);
				reportedSize = receivedCount;
				Logger.debug("Received " + receivedCount + " bytes " + TimeUtils.bps(size, reported));
				reported = now;
			}
			break;
		}
		synchronized (this) {
			if (fileOut != null) {
				try {
					fileOut.write((char) data);
				} catch (IOException e) {
					Logger.debug("file write error", e);
					closeFile();
				}
			}
		}
	}

	private void printdataReceivedL2CAP(byte[] data, int length) {
		switch (interpretData) {
		case interpretDataChars:
			int messageLength = length;
			if ((length > 0) && (data[length - 1] == '\n')) {
				messageLength = length - 1;
			}
			StringBuffer buf = new StringBuffer();
			if (messageLength != 0) {
				buf.append(StringUtils.toBinaryText(new StringBuffer(new String(data, 0, messageLength))));
			}
			buf.append(" (").append(length).append(")");
			Logger.debug("cc:" + buf.toString());
			break;
		case interpretDataStats:
			long now = System.currentTimeMillis();
			if (now - reported > 5 * 1000) {
				int size = (int) (receivedCount - reportedSize);
				reportedSize = receivedCount;
				Logger.debug("Received " + receivedPacketsCount + " packet(s), " + receivedCount + " bytes "
						+ TimeUtils.bps(size, reported));
				reported = now;
			}
			break;
		}
		synchronized (this) {
			if (fileOut != null) {
				try {
					fileOut.write(data, 0, length);
				} catch (IOException e) {
					Logger.debug("file write error", e);
					closeFile();
				}
			}
		}
	}

	public void shutdown() {
		stoped = true;
		if (c != null) {
			c.shutdown();
		}
		c = null;
		closeFile();
	}

	private synchronized void closeFile() {
		if (fileOut != null) {
			try {
				fileOut.flush();
			} catch (IOException ignore) {
			}
			IOUtils.closeQuietly(fileOut);
			fileOut = null;
		}
	}

	public void updateDataReceiveType(int type, boolean saveToFile) {
		interpretData = type;

		if ((!saveToFile) && (fileOut != null)) {
			closeFile();
		} else if ((saveToFile) && (fileOut == null)) {
			SimpleDateFormat fmt = new SimpleDateFormat("MM-dd_HH-mm-ss");
			File file = new File("data-" + BluetoothTypesInfo.extractBluetoothAddress(serverURL)
					+ fmt.format(new Date()) + ".bin");
			try {
				fileOut = new FileOutputStream(file);
				Logger.info("saving data to file " + file.getAbsolutePath());
			} catch (IOException e) {
			}
		}
	}

	public void send(final byte data[]) {
		Thread t = new Thread("ClientConnectionSendThread" + (++connectionCount)) {
			public void run() {
				try {
					if (rfcomm) {
						((ConnectionHolderStream) c).os.write(data);
					} else {
						((ConnectionHolderL2CAP) c).channel.send(data);
					}
					Logger.debug("data " + data.length + " sent");
				} catch (IOException e) {
					Logger.error("Communication error", e);
				}
			}
		};
		t.setDaemon(true);
		t.start();
	}
}