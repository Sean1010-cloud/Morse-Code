import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.Scanner;

public class SerialTcpClient {
	private final ClientConfig config;
	private final BlockingQueue<Message> messageQueue;
	private final List<Consumer<String>> messageListeners;
	private static final AtomicBoolean isRunning = new AtomicBoolean(true);
	private static volatile Socket currentSocket;
	private volatile long lastHeartbeatResponse;
	
	public static class Message {
		private final String type;        // "TEXT" or "BINARY"
		private final String targetPort;  // Serial port name
		private final String key;         // Message key
		private final String value;       // Message value or hex string for binary
		
		public Message(String type, String targetPort, String key, String value) {
			this.type = type.toUpperCase();
			this.targetPort = targetPort;
			this.key = key;
			this.value = value;
			
			if (!type.equalsIgnoreCase("TEXT") && !type.equalsIgnoreCase("BINARY")) {
				throw new IllegalArgumentException("Type must be TEXT or BINARY");
			}
		}
		
		public String format() {
			return String.format("%s %s %s %s", type, targetPort, key, value);
		}
	}
	
	public static class ClientConfig {
		private final int initialReconnectDelay;
		private final int maxReconnectDelay;
		private final int maxReconnectAttempts;
		private final int socketTimeout;
		private final int connectTimeout;
		private final int heartbeatInterval;
		private final String heartbeatMessage;
		private final boolean keepAlive;
		
		private ClientConfig(Builder builder) {
			this.initialReconnectDelay = builder.initialReconnectDelay;
			this.maxReconnectDelay = builder.maxReconnectDelay;
			this.maxReconnectAttempts = builder.maxReconnectAttempts;
			this.socketTimeout = builder.socketTimeout;
			this.connectTimeout = builder.connectTimeout;
			this.heartbeatInterval = builder.heartbeatInterval;
			this.heartbeatMessage = builder.heartbeatMessage;
			this.keepAlive = builder.keepAlive;
		}
		
		public static class Builder {
			private int initialReconnectDelay = 1000;
			private int maxReconnectDelay = 2000;
			private int maxReconnectAttempts = 0;
			private int socketTimeout = 8000; // 30 seconds
			private int connectTimeout = 5000; // 5 seconds
			private int heartbeatInterval = 1200; // 15 seconds
			private String heartbeatMessage = "HEARTBEAT";
			private boolean keepAlive = true;
			
			// Builder methods remain the same
			public Builder initialReconnectDelay(int delay) {
				this.initialReconnectDelay = delay;
				return this;
			}
			
			public Builder maxReconnectDelay(int delay) {
				this.maxReconnectDelay = delay;
				return this;
			}
			
			public Builder maxReconnectAttempts(int attempts) {
				this.maxReconnectAttempts = attempts;
				return this;
			}
			
			public Builder socketTimeout(int timeout) {
				this.socketTimeout = timeout;
				return this;
			}
			
			public Builder connectTimeout(int timeout) {
				this.connectTimeout = timeout;
				return this;
			}
			
			public Builder heartbeatInterval(int interval) {
				this.heartbeatInterval = interval;
				return this;
			}
			
			public Builder heartbeatMessage(String message) {
				this.heartbeatMessage = message;
				return this;
			}
			
			public Builder keepAlive(boolean keepAlive) {
				this.keepAlive = keepAlive;
				return this;
			}
			
			public ClientConfig build() {
				return new ClientConfig(this);
			}
		}
	}
	
	public SerialTcpClient(ClientConfig config) {
		this.config = config;
		this.messageQueue = new LinkedBlockingQueue<>();
		this.messageListeners = new CopyOnWriteArrayList<>();
		this.lastHeartbeatResponse = System.currentTimeMillis();
	}
	
	public void addMessageListener(Consumer<String> listener) {
		messageListeners.add(listener);
	}
	
	public void removeMessageListener(Consumer<String> listener) {
		messageListeners.remove(listener);
	}
	
	// Send text message to serial port
	public void sendText(String port, String key, String value) throws InterruptedException {
		Message msg = new Message("TEXT", port, key, value);
		messageQueue.put(msg);
	}
	
	// Send binary message to serial port (value should be hex string)
	public void sendBinary(String port, String key, String hexValue) throws InterruptedException {
		Message msg = new Message("BINARY", port, key, hexValue);
		messageQueue.put(msg);
	}
	
	private void handleConnection(String serverHost, int serverPort) throws IOException {
		try (Socket socket = new Socket()) {
			currentSocket = socket;
			
			socket.setKeepAlive(config.keepAlive);
			socket.setSoTimeout(config.socketTimeout);
			socket.connect(new InetSocketAddress(serverHost, serverPort), config.connectTimeout);
			
			System.out.println("Connected to server: " + serverHost + ":" + serverPort);
			
			try (PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
			BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
				
				Thread responseThread = startResponseHandler(in);
				Thread heartbeatThread = startHeartbeatSender(out);
				
				while (!socket.isClosed() && isRunning.get()) {
					Message message = messageQueue.poll(100, TimeUnit.MILLISECONDS);
					if (message != null) {
						try {
							String formattedMessage = message.format();
							out.println(formattedMessage);
							
							if (!out.checkError()) {
								System.out.println("Sent: " + formattedMessage);
							} else {
								throw new IOException("Failed to send message - connection lost");
							}
						} catch (Exception e) {
							System.err.println("Error sending message: " + e.getMessage());
							throw e;
						}
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				closeCurrentSocket();
			}
		}
	}
	
	private Thread startResponseHandler(BufferedReader in) {
		Thread responseThread = new Thread(() -> {
			try {
				String response;
				while ((response = in.readLine()) != null) {
					if (response.equals(config.heartbeatMessage)) {
						lastHeartbeatResponse = System.currentTimeMillis();
						//System.out.println(config.heartbeatMessage); // Echo heartbeat back
						continue;
					}
					
					// Handle "RECEIVED" messages from server
					if (response.startsWith("RECEIVED")) {
						for (Consumer<String> listener : messageListeners) {
							try {
								listener.accept(response);
							} catch (Exception e) {
								System.err.println("Error in message listener: " + e.getMessage());
							}
						}
					} else {
						// Handle other server messages
						System.out.println("Server: " + response);
					}
				}
			} catch (SocketTimeoutException e) {
				System.err.println("Server response timeout - connection may be lost");
			} catch (IOException e) {
				if (isRunning.get()) {
					System.err.println("Lost connection to server: " + e.getMessage());
				}
			}
		});
		responseThread.setDaemon(true);
		responseThread.start();
		return responseThread;
	}
	
	private Thread startHeartbeatSender(PrintWriter out) {
		Thread heartbeatThread = new Thread(() -> {
			while (!Thread.currentThread().isInterrupted() && isRunning.get()) {
				try {
					Thread.sleep(config.heartbeatInterval);
					if (System.currentTimeMillis() - lastHeartbeatResponse > config.socketTimeout) {
						throw new IOException("No heartbeat response from server");
					}
					out.println(config.heartbeatMessage);
					if (out.checkError()) {
						throw new IOException("Failed to send heartbeat - connection lost");
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				} catch (IOException e) {
					System.err.println("Heartbeat failed: " + e.getMessage());
					break;
				}
			}
		});
		heartbeatThread.setDaemon(true);
		heartbeatThread.start();
		return heartbeatThread;
	}
	
	private void startInputHandler() {
		Thread inputThread = new Thread(() -> {
			try (BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in))) {
				System.out.println("Enter commands in format: <TYPE> <PORT> <KEY> <VALUE>");
				System.out.println("Example: TEXT COM1 testKey HelloWorld");
				System.out.println("        BINARY COM2 binKey DEADBEEF");
				System.out.println("Enter 'exit' to quit");
				
				String input;
				while (isRunning.get() && (input = consoleReader.readLine().trim()) != null) {
					if ("exit".equalsIgnoreCase(input.trim())) {
						isRunning.set(false);
						closeCurrentSocket();
						break;
					}
					
					try {
						String[] parts = input.split(" ", 4);
						if (parts.length == 4) {
							Message msg = new Message(parts[0], parts[1], parts[2], parts[3]);
							messageQueue.add(msg);
						} else {
							System.out.println("Invalid format. Use: <TYPE> <PORT> <KEY> <VALUE>");
						}
					} catch (IllegalArgumentException e) {
						System.out.println("Error: " + e.getMessage());
					}
				}
			} catch (IOException e) {
				System.err.println("Error reading console input: " + e.getMessage());
			}
		});
		inputThread.setDaemon(true);
		inputThread.start();
	}
	
	public void start(String serverHost, int serverPort, boolean interactive) {
		
		if (interactive) startInputHandler();
		
		int reconnectAttempts = 0;
		int currentDelay = config.initialReconnectDelay;
		
		while (isRunning.get()) {
			try {
				if (config.maxReconnectAttempts > 0 && reconnectAttempts >= config.maxReconnectAttempts) {
					System.err.println("Maximum reconnection attempts reached. Exiting...");
					break;
				}
				
				handleConnection(serverHost, serverPort);
				reconnectAttempts = 0;
				currentDelay = config.initialReconnectDelay;
				
			} catch (Exception e) {
				reconnectAttempts++;
				System.err.println(String.format("Connection attempt %d failed: %s",
				reconnectAttempts, e.getMessage()));
				
				try {
					System.err.println("Waiting " + (currentDelay / 1000) + " seconds before reconnecting...");
					Thread.sleep(currentDelay);
					currentDelay = Math.min(currentDelay * 2, config.maxReconnectDelay);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
	}
	
	private void closeCurrentSocket() {
		if (currentSocket != null && !currentSocket.isClosed()) {
			try {
				currentSocket.close();
			} catch (IOException e) {
				System.err.println("Error closing socket: " + e.getMessage());
			}
		}
	}
	
	public static void main(String[] args) {
		if (args.length != 3) {
			System.out.println("Usage: java SerialTcpClient <server_host> <server_port> <serial_port>");
			return;
		}
		
		String serverHost = args[0];
		int serverPort = Integer.parseInt(args[1]);
		String serialPort = args[2]; // Would be something like "/dev/cu.usbmodem11101" in Mac/linux ... Windows something like COM4
		
		ClientConfig config = new ClientConfig.Builder().build();
		SerialTcpClient client = new SerialTcpClient(config);
		
		// Add message listener for received messages
		client.addMessageListener(message -> System.out.println("Serial message: " + message));
		
		boolean interactive = false;
		
		if (interactive) {
			// Start client interactive mode
			client.start(serverHost, serverPort, interactive);
		} else {
			// If you want API mode ... start client in a separate thread since it's blocking
			Thread clientThread = new Thread(() -> client.start(serverHost, serverPort, interactive));
			clientThread.start();

			final int long_sleep = 500; //pause between letters
			final int short_sleep = 250; //pause between dots/dashes
			final String dot = "20"; //dot
			final String dash = "50"; //dash

			try {
				while (true) {
					Scanner scunner = new Scanner(System.in);
					System.out.println("plz enter word");
					String word = scunner.nextLine();
					String sentence = word.trim();
						char[] letter = sentence.toCharArray();
					for (char n : letter) {
						if (n == 'A' || n == 'a'){
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);

							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
						} else if (n == 'B' || n == 'b') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
						} else if (n == 'C' || n == 'c') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
						}
						if (n == 'D' || n == 'd') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
						}
						else if (n == 'E' || n == 'e') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
						}
						else if (n == 'F' || n == 'f') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
						}
						else if (n == 'G' || n == 'g') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
						}
						else if (n == 'H' || n == 'h') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
						}
						else if (n == 'I' || n == 'i') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
						}

						else if (n == 'J' || n == 'j') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
						}

						else if (n == 'K' || n == 'k') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
						}

						else if (n == 'L' || n == 'l') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
						}

						else if (n == 'M' || n == 'm') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
						}

						else if (n == 'N' || n == 'n') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
						}

						else if (n == 'O' || n == 'o') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
						}

						else if (n == 'P' || n == 'p') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
						}

						else if (n == 'Q' || n == 'q') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
						}

						else if (n == 'R' || n == 'r') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
						}

						else if (n == 'S' || n == 's') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
						}

						else if (n == 'T' || n == 't') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
						}

						else if (n == 'U' || n == 'u') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
						}

						else if (n == 'V' || n == 'v') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
						}

						else if (n == 'W' || n == 'w') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
						}

						else if (n == 'X' || n == 'x') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
						}

						else if (n == 'Y' || n == 'y') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
						}

						else if (n == 'Z' || n == 'z') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
						} else if (n == ' ') {
							Thread.sleep(long_sleep);
						}
						else if (n == '1') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
						}
						else if (n == '2') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
						}
						else if (n == '3') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
						}

						else if (n == '4') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
						}

						else if (n == '5') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
						}

						else if (n == '6') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
						}

						else if (n == '7') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
						}

						else if (n == '8') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
						}

						else if (n == '9') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
						}

						else if (n == '0') {
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
						}
						else if (n == '.') { // Period
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
						}

						else if (n == ',') { // Comma
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
						}

						else if (n == '?') { // Question mark
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
						}
						else if (n == ':') { // Colon
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
						}

						else if (n == ';') { // Semicolon
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
						}

						else if (n == '+') { // Plus
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
						}

						else if (n == '-') { // Minus
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
						}

						else if (n == '=') { // Equals
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
						}

						else if (n == '/') { // Forward slash
							Thread.sleep(long_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dash);
							Thread.sleep(short_sleep);
							client.sendBinary(serialPort, "LED_TOGGEL", dot);
						}
						else {
							System.out.println("Unknown character sent. Skipping.");
						}
					}
				}
			} catch (InterruptedException e){
					e.printStackTrace();

			}
		}
	}
}

