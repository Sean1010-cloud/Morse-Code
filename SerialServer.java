import com.fazecast.jSerialComm.SerialPort;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class SerialServer {
	private static final long HEARTBEAT_INTERVAL_MS = 2000; // 2 seconds
	private static final long CLIENT_TIMEOUT_MS = 9000;     // 9 seconds
	
	private final SerialCommunication serialComm;
	private final Set<ClientHandler> clientHandlers = ConcurrentHashMap.newKeySet();
	private final ScheduledExecutorService heartbeatScheduler = Executors.newScheduledThreadPool(1);
	
	public SerialServer(List<PortConfig> ports) {
		serialComm = new SerialCommunication();
		
		serialComm.subscribe(message -> {
			StringBuilder sb = new StringBuilder();
			if (message.getType() == SerialCommunication.MessageType.TEXT) {
				sb.append("RECEIVED TEXT ")
				.append(message.getSourcePort()).append(" ")
				.append(message.getKey()).append(" ")
				.append(new String(message.getValue()));
			} else {
				sb.append("RECEIVED BINARY ")
				.append(message.getSourcePort()).append(" ")
				.append(message.getKey()).append(" ");
				for (byte b : message.getValue()) {
					sb.append(String.format("%02X", b));
				}
			}
			
			String outbound = sb.toString();
			broadcast(outbound);
		});
		
		for (PortConfig cfg : ports) {
			System.out.printf("Opening port: %s, baud=%d, dataBits=%d, parity=%d, stopBits=%d%n",
			cfg.port, cfg.baudRate, cfg.dataBits, cfg.parity, cfg.stopBits);
			try {
				serialComm.addSerialPort(cfg.port, cfg.baudRate,
				cfg.dataBits, cfg.stopBits, cfg.parity);
				System.out.println("  -> Opened successfully.");
			} catch (Exception e) {
				System.err.printf("  -> Failed to open %s: %s%n", cfg.port, e.getMessage());
			}
		}
		
		// Start heartbeat monitoring
		startHeartbeatMonitoring();
	}
	
	private void startHeartbeatMonitoring() {
		heartbeatScheduler.scheduleAtFixedRate(() -> {
			long currentTime = System.currentTimeMillis();
			synchronized (clientHandlers) {
				Iterator<ClientHandler> iterator = clientHandlers.iterator();
				while (iterator.hasNext()) {
					ClientHandler handler = iterator.next();
					if (currentTime - handler.getLastHeartbeatTime() > CLIENT_TIMEOUT_MS) {
						System.out.println("Client " + handler.getClientAddress() + " timed out, closing connection");
						handler.close();
						iterator.remove();
					} else {
						handler.sendHeartbeat();
					}
				}
			}
		}, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
	}
	
	public void startServer(int tcpPort) throws IOException {
		ServerSocket serverSocket = new ServerSocket(tcpPort);
		System.out.println("SerialServer started. Listening on TCP port " + tcpPort);
		
		while (true) {
			Socket clientSocket = serverSocket.accept();
			ClientHandler handler = new ClientHandler(clientSocket);
			clientHandlers.add(handler);
			handler.start();
		}
	}
	
	private void broadcast(String message) {
		synchronized (clientHandlers) {
			for (ClientHandler handler : clientHandlers) {
				handler.send(message);
			}
		}
	}
	
	public void shutdown() {
		System.out.println("Shutting down the SerialServer...");
		heartbeatScheduler.shutdown();
		try {
			heartbeatScheduler.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		
		serialComm.close();
		
		for (ClientHandler handler : clientHandlers) {
			handler.close();
		}
		clientHandlers.clear();
	}
	
	public static class PortConfig {
		public String port;
		public int baudRate;
		public int dataBits;
		public int stopBits;
		public int parity;
		
		public PortConfig(String port, int baudRate, int dataBits, int parity, int stopBits) {
			this.port = port;
			this.baudRate = baudRate;
			this.dataBits = dataBits;
			this.parity = parity;
			this.stopBits = stopBits;
		}
	}
	
	private class ClientHandler extends Thread {
		private final Socket socket;
		private PrintWriter out;
		private BufferedReader in;
		private boolean active = true;
		private volatile long lastHeartbeatTime;
		private final String clientAddress;
		
		public ClientHandler(Socket socket) {
			this.socket = socket;
			this.lastHeartbeatTime = System.currentTimeMillis();
			this.clientAddress = socket.getRemoteSocketAddress().toString();
		}
		
		public String getClientAddress() {
			return clientAddress;
		}
		
		public long getLastHeartbeatTime() {
			return lastHeartbeatTime;
		}
		
		public void updateHeartbeat() {
			this.lastHeartbeatTime = System.currentTimeMillis();
		}
		
		public void sendHeartbeat() {
			send("HEARTBEAT");
		}
		
		@Override
		public void run() {
			try {
				out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
				in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
				
				out.println("Welcome to NAMO Serial Server!");
				
				String line;
				while (active && (line = in.readLine()) != null) {
					// Handle heartbeat response
					if ("HEARTBEAT".equals(line.trim())) {
						updateHeartbeat();
						continue;
					}
					
					line = line.trim();
					if (line.equalsIgnoreCase("QUIT")) {
						out.println("Goodbye!");
						break;
					}
					
					String[] tokens = line.split(" ", 4);
					if (tokens.length < 4) {
						out.println("ERROR: Invalid command format. Expected 4 tokens minimum.");
						continue;
					}
					
					String cmdType = tokens[0].toUpperCase();
					String targetPort = tokens[1];
					String key = tokens[2];
					String value = tokens[3];
					
					try {
						switch (cmdType) {
							case "TEXT":
							serialComm.publishText(key, value, targetPort);
							out.printf("Sent TEXT to %s (key=%s, value=%s)\n", targetPort, key, value);
							break;
							
							case "BINARY":
							byte[] data = hexStringToByteArray(value);
							serialComm.publishBinary(key, data, targetPort);
							out.printf("Sent BINARY to %s (key=%s, %d bytes)\n",
							targetPort, key, data.length);
							break;

							case "CSV":
								String[] CSV_part = value.split(",");
								if (CSV_part.length >= 2) {
									int togglecount = Integer.parseInt(CSV_part[0].trim());
									int multidelay = Integer.parseInt(CSV_part[1].trim());
									data = new byte[]{(byte) togglecount, (byte) multidelay};
									data[0] = (byte) togglecount;
									data[1] = (byte) multidelay;
									serialComm.publishBinary("LED_TOGGLE", data, targetPort);
									out.printf("Sent CSV-converted BINARY to %s (LED_TOGGLE, %d toggles, delay multiplier %d)\n",
											targetPort, togglecount, multidelay);
								}
								else {
								out.println("ERROR: CSV format invalid. Expected format: toggleCount,delayMultiplier");
								}
							break;
							
							default:
							out.println("ERROR: Unrecognized command. Use TEXT, BINARY, or CSV file.");
							break;
						}
					} catch (Exception e) {
						out.println("ERROR: " + e.getMessage());
					}
				}
				
			} catch (IOException e) {
				System.err.println("ClientHandler encountered an error: " + e.getMessage());
			} finally {
				close();
			}
		}
		
		public void send(String msg) {
			if (out != null && active) {
				out.println(msg);
			}
		}
		
		public void close() {
			active = false;
			try {
				if (out != null) out.close();
				if (in != null) in.close();
				if (socket != null && !socket.isClosed()) socket.close();
			} catch (IOException ignored) {
			}
			clientHandlers.remove(this);
		}
	}
	
	private static byte[] hexStringToByteArray(String hex) {
		hex = hex.replaceAll("\\s+", "");
		if (hex.length() % 2 != 0) {
			throw new IllegalArgumentException("Hex string must have an even number of characters");
		}
		int len = hex.length() / 2;
		byte[] data = new byte[len];
		for (int i = 0; i < len; i++) {
			data[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
		}
		return data;
	}
	
	public static void main(String[] args) {
		if (args.length < 2) {
			System.out.println("Usage: java SerialServer <tcpPort> <port> <baud> <config> [<port> <baud> <config> ...]");
			System.out.println(" Example: java SerialServer 9000 COM1 9600 8N1 COM2 115200 8N1");
			System.exit(0);
		}
		
		int tcpPort = Integer.parseInt(args[0]);
		
		List<PortConfig> portConfigs = new ArrayList<>();
		int i = 1;
		while (i < args.length) {
			if (i + 2 >= args.length) {
				System.err.println("ERROR: Each serial port config requires: <port> <baud> <config>");
				System.exit(1);
			}
			String portName = args[i];
			int baud = Integer.parseInt(args[i + 1]);
			String config = args[i + 2];
			i += 3;
			
			int dataBits = Character.getNumericValue(config.charAt(0));
			if (dataBits < 5 || dataBits > 8) {
				throw new IllegalArgumentException("Invalid data bits: " + dataBits);
			}
			
			char parityChar = Character.toUpperCase(config.charAt(1));
			int parity;
			switch (parityChar) {
				case 'N': parity = SerialPort.NO_PARITY;   break;
				case 'E': parity = SerialPort.EVEN_PARITY; break;
				case 'O': parity = SerialPort.ODD_PARITY;  break;
				case 'M': parity = SerialPort.MARK_PARITY; break;
				case 'S': parity = SerialPort.SPACE_PARITY;break;
				default:
				throw new IllegalArgumentException("Invalid parity: " + parityChar);
			}
			
			int stopBitsVal = Character.getNumericValue(config.charAt(2));
			int stopBits;
			switch (stopBitsVal) {
				case 1: stopBits = SerialPort.ONE_STOP_BIT; break;
				case 2: stopBits = SerialPort.TWO_STOP_BITS; break;
				default:
				throw new IllegalArgumentException("Invalid stop bits: " + stopBitsVal);
			}
			
			portConfigs.add(new PortConfig(portName, baud, dataBits, parity, stopBits));
		}
		
		SerialServer server = new SerialServer(portConfigs);
		
		try {
			server.startServer(tcpPort);
		} catch (IOException e) {
			System.err.println("Failed to start server: " + e.getMessage());
			server.shutdown();
		}
	}
}	

