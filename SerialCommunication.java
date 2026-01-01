import com.fazecast.jSerialComm.SerialPort;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.Thread;

/**
* Multi-port serial communication manager with automatic reconnect
* and explicit read() error detection (read == -1).
*
* Message Format (big-endian for lengths):
*  --------------------------------
*  | keyLength (4 bytes)         |
*  | valueLength (4 bytes)       |
*  | key (keyLength bytes)       |
*  | value (valueLength bytes)   |
*  | messageType (1 byte)        |
*  --------------------------------
*/
public class SerialCommunication {
	
	private static final int RECONNECT_DELAY_MS = 300; // Delay between reconnection attempts
	
	// Message Types
	public enum MessageType {
		TEXT(0),
		BINARY(1);
		
		private final int value;
		MessageType(int val) { this.value = val; }
		public int getValue() { return value; }
		
		public static MessageType fromByte(byte b) {
			return (b == 0) ? TEXT : BINARY;
		}
	}
	
	// Message Class
	public static class Message {
		private final String key;
		private final byte[] value;
		private final MessageType type;
		private final String sourcePort;
		
		public Message(String key, byte[] value, MessageType type, String sourcePort) {
			this.key = key;
			this.value = value;
			this.type = type;
			this.sourcePort = sourcePort;
		}
		
		public String getKey()        { return key; }
		public byte[] getValue()      { return value; }
		public MessageType getType()  { return type; }
		public String getSourcePort() { return sourcePort; }
	}
	
	// Subscriber interface
	public interface Subscriber {
		void onMessage(Message message);
	}
	
	// Internal SerialPortManager
	private class SerialPortManager {
		private final String portName;
		private final int baudRate, dataBits, stopBits, parity;
		private final AtomicBoolean running = new AtomicBoolean(true);
		private AtomicBoolean isConnected = new AtomicBoolean(false);
		
		// The jSerialComm port
		private volatile SerialPort serialPort;
		// Worker threads
		private Thread readThread;
		private Thread writeThread;
		private Thread reconnectThread;
		
		// Outgoing message queue
		private final BlockingQueue<Message> sendQueue = new LinkedBlockingQueue<>();
		
		public SerialPortManager(String portName, int baudRate, int dataBits, int stopBits, int parity) {
			this.portName = portName;
			this.baudRate = baudRate;
			this.dataBits = dataBits;
			this.stopBits = stopBits;
			this.parity = parity;
			
			// Attempt to open port initially
			if (!initializePort()) {
				// Start a reconnection loop if the port can't open right now
				startReconnectionThread();
			}
		}
		
		/**
		* Attempt to open the port. If successful, spawn read/write threads and return true.
		* If unsuccessful, return false.
		*/
		private boolean initializePort() {
			try {
				serialPort = SerialPort.getCommPort(portName);
				serialPort.setBaudRate(baudRate);
				serialPort.setNumDataBits(dataBits);
				serialPort.setNumStopBits(stopBits);
				serialPort.setParity(parity);
				
				// Non-blocking I/O
				serialPort.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);
				
				if (!serialPort.openPort()) {
					System.err.printf("[ERROR] Failed to open port: %s%n", portName);
					this.isConnected.set(false);
					return false;
				}
				
				System.out.printf("[INFO] Port %s opened successfully.%n", portName);
				startThreads();
				this.isConnected.set(true);
				return true;
				
			} catch (Exception e) {
				System.err.printf("[ERROR] Exception while initializing port %s: %s%n",
				portName, e.getMessage());
				this.isConnected.set(false);
				return false;
			}
		}
		
		/**
		* Start read and write threads
		*/
		private void startThreads() {
			// Prevent double start
			if (readThread != null && readThread.isAlive()) {
				return; // Already running
			}
			if (writeThread != null && writeThread.isAlive()) {
				return; // Already running
			}
			
			// READ THREAD
			readThread = new Thread(() -> {
				final ByteBuffer headerBuf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
				
				while (running.get()) {
					try {
						if (!isConnected.get()){
							Thread.sleep(10);
							continue;
						}
						
						headerBuf.clear();
						if (!readFully(headerBuf)) {
							// Not enough data right now or disconnection
							Thread.sleep(10);
							continue;
						}
						
						headerBuf.flip();
						int keyLength   = headerBuf.getInt();
						int valueLength = headerBuf.getInt();
						
						if (keyLength <= 0 || keyLength > 1024 ||
						valueLength <= 0 || valueLength > 4096) {
							System.err.printf("[ERROR: %s] Invalid header (keyLen=%d, valueLen=%d)%n",
							portName, keyLength, valueLength);
							continue;
						}
						
						// Read key
						byte[] keyBytes = new byte[keyLength];
						if (!readFully(keyBytes)) {
							System.err.printf("[ERROR: %s] Partial key read%n", portName);
							continue;
						}
						
						// Read value
						byte[] valueBytes = new byte[valueLength];
						if (!readFully(valueBytes)) {
							System.err.printf("[ERROR: %s] Partial value read%n", portName);
							continue;
						}
						
						// Read type
						byte[] typeByte = new byte[1];
						if (!readFully(typeByte)) {
							System.err.printf("[ERROR: %s] Partial type read%n", portName);
							continue;
						}
						
						MessageType msgType = MessageType.fromByte(typeByte[0]);
						System.out.printf("[DEBUG: %s] Received: key=%s, valueLen=%d, type=%s%n",
						portName, new String(keyBytes), valueLength, msgType);
						
						Message inbound = new Message(new String(keyBytes), valueBytes, msgType, portName);
						notifySubscribers(inbound);
						
					} catch (Exception e) {}/* catch (InterruptedException ie) {
						// Possibly shutting down
						if (!running.get()) {
							// Exiting
							break;
						}
					} catch (Exception e) {
						System.err.printf("[ERROR: %s] Exception in read thread: %s%n",
						portName, e.getMessage());
						if (running.get()) {
							handleDisconnection();
							break;
						}
					}*/
				}
			}, "ReadThread-" + portName);
			readThread.start();
			
			// WRITE THREAD
			writeThread = new Thread(() -> {
				while (running.get()) {
					try {
						if (!isConnected.get()){
							Thread.sleep(10);
							continue;
						}
						
						Message msg = sendQueue.take();
						
						byte[] keyBytes   = msg.getKey().getBytes();
						byte[] valueBytes = msg.getValue();
						
						ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
						header.putInt(keyBytes.length).putInt(valueBytes.length);
						
						System.out.printf("[DEBUG: %s] Sending message: key=%s, valueLen=%d, type=%s%n",
						portName, msg.getKey(), valueBytes.length, msg.getType());
						
						// Write the header
						int written = serialPort.writeBytes(header.array(), 8);
						if (written < 0) {
							System.err.printf("[ERROR: %s] Write error on header (disconnect?)%n", portName);
							handleDisconnection();
							continue;
							//break;
						}
						
						// Write key
						written = serialPort.writeBytes(keyBytes, keyBytes.length);
						if (written < 0) {
							System.err.printf("[ERROR: %s] Write error on key (disconnect?)%n", portName);
							handleDisconnection();
							continue; //break;
						}
						
						// Write value
						written = serialPort.writeBytes(valueBytes, valueBytes.length);
						if (written < 0) {
							System.err.printf("[ERROR: %s] Write error on value (disconnect?)%n", portName);
							handleDisconnection();
							continue;//break;
						}
						
						// Write type
						written = serialPort.writeBytes(new byte[]{(byte) msg.getType().getValue()}, 1);
						if (written < 0) {
							System.err.printf("[ERROR: %s] Write error on type (disconnect?)%n", portName);
							handleDisconnection();
							continue; //break;
						}
						
					} catch (Exception e) {} /*catch (InterruptedException e) {
						// Possibly shutting down
						Thread.currentThread().interrupt();
						break;
					} catch (Exception e) {
						System.err.printf("[ERROR: %s] Exception in write thread: %s%n",
						portName, e.getMessage());
						if (running.get()) {
							handleDisconnection();
							break;
						}
					}*/
				}
			}, "WriteThread-" + portName);
			writeThread.start();
		}
		
		/**
		* Called if the port is disconnected or an I/O error occurs.
		*/
		private void handleDisconnection() {
			if (!isConnected.get()) return;
			System.err.printf("[WARN] Port %s disconnected or error occurred. Will attempt to reconnect...%n", portName);
			
			try{
				Thread.sleep(1000);
			} catch(Exception e){}
			
			// Stop read/write threads
			//if (readThread != null) readThread.interrupt();
			//if (writeThread != null) writeThread.interrupt();
			
			// Close port
			if (serialPort != null && serialPort.isOpen()) {
				serialPort.closePort();
			}
			isConnected.set(false);
			// Start reconnection
			startReconnectionThread();
		}
		
		/**
		* Launches a separate thread to periodically try re-opening the port
		*/
		private void startReconnectionThread() {
			if (this.isConnected.get()) return;
			// If there's already a reconnect thread running, skip
			if (reconnectThread != null && reconnectThread.isAlive()) {
				return;
			}
			
			reconnectThread = new Thread(() -> {
				while (running.get()) {
					try {
						Thread.sleep(RECONNECT_DELAY_MS);
						System.out.printf("[INFO] Attempting to reconnect %s...%n", portName);
						
						if (initializePort()) {
							System.out.printf("[INFO] Successfully reconnected to %s%n", portName);
							break;  // Done reconnecting
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					} catch (Exception e) {
						System.err.printf("[ERROR: %s] Reconnect attempt failed: %s%n",
						portName, e.getMessage());
					}
				}
			}, "ReconnectThread-" + portName);
			reconnectThread.start();
		}
		
		/**
		* Read exactly `buf.remaining()` bytes into `buf`.
		* If read returns -1, treat as disconnection.
		*/
		private boolean readFully(ByteBuffer buf) throws InterruptedException {
			while (buf.hasRemaining() && running.get()) {
				byte[] tmp = new byte[1];
				int read = serialPort.readBytes(tmp, 1);
				
				if (read == -1) {
					handleDisconnection();
					return false;
				}
				
				if (read > 0) {
					buf.put(tmp[0]);
				} else {
					Thread.sleep(2); // no data yet
				}
			}
			return !buf.hasRemaining();
		}
		
		/**
		* Overload to read into a byte[]
		* If read returns -1, treat as disconnection.
		*/
		private boolean readFully(byte[] array) throws InterruptedException {
			int offset = 0;
			while (offset < array.length && running.get()) {
				int read = serialPort.readBytes(array, array.length - offset, offset);
				
				if (read == -1) {
					System.err.printf("[ERROR: %s] read == -1 (disconnect detected)%n", portName);
					handleDisconnection();
					return false;
				}
				
				if (read > 0) {
					offset += read;
				} else {
					Thread.sleep(2); // no data yet
				}
			}
			return (offset >= array.length);
		}
		
		/**
		* Enqueue a message to be sent
		*/
		public void send(Message msg) {
			try {
				sendQueue.put(msg);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		
		/**
		* Fully close the manager
		*/
		public void close() {
			if (!running.compareAndSet(true, false)) {
				return; // Already closed
			}
			
			if (reconnectThread != null) {
				reconnectThread.interrupt();
			}
			if (readThread != null) {
				readThread.interrupt();
			}
			if (writeThread != null) {
				writeThread.interrupt();
			}
			if (serialPort != null && serialPort.isOpen()) {
				serialPort.closePort();
			}
			
			System.out.printf("[INFO] Port %s closed.%n", portName);
		}
	}
	

	// Aggregation of Ports + Subscription
	private final Map<String, SerialPortManager> portManagers = new ConcurrentHashMap<>();
	private final Set<Subscriber> subscribers = ConcurrentHashMap.newKeySet();
	
	/**
	* Add a new serial port
	*/
	public void addSerialPort(String portName, int baudRate, int dataBits, int stopBits, int parity) {
		portManagers.computeIfAbsent(portName, 
		p -> new SerialPortManager(portName, baudRate, dataBits, stopBits, parity));
	}
	
	/**
	* Publish a TEXT message
	*/
	public void publishText(String key, String value, String targetPort) {
		publish(key, value.getBytes(), MessageType.TEXT, targetPort);
	}
	
	/**
	* Publish a BINARY message
	*/
	public void publishBinary(String key, byte[] value, String targetPort) {
		publish(key, value, MessageType.BINARY, targetPort);
	}
	
	/**
	* Generic publish
	*/
	private void publish(String key, byte[] value, MessageType type, String targetPort) {
		Message msg = new Message(key, value, type, null);
		if (targetPort != null) {
			SerialPortManager spm = portManagers.get(targetPort);
			if (spm != null) {
				spm.send(msg);
			} else {
				System.err.printf("[ERROR] Port %s not found.%n", targetPort);
			}
		} else {
			// broadcast
			for (SerialPortManager spm : portManagers.values()) {
				spm.send(msg);
			}
		}
	}
	
	/**
	* Subscribe
	*/
	public void subscribe(Subscriber subscriber) {
		subscribers.add(subscriber);
	}
	
	/**
	* Unsubscribe
	*/
	public void unsubscribe(Subscriber subscriber) {
		subscribers.remove(subscriber);
	}
	
	/**
	* Close all ports
	*/
	public void close() {
		portManagers.values().forEach(SerialPortManager::close);
		portManagers.clear();
	}
	
	/**
	* Notify all subscribers
	*/
	private void notifySubscribers(Message message) {
		for (Subscriber sub : subscribers) {
			sub.onMessage(message);
		}
	}
	
	/**
	* List available system ports
	*/
	public static List<String> listAvailablePorts() {
		List<String> result = new ArrayList<>();
		for (SerialPort sp : SerialPort.getCommPorts()) {
			result.add(sp.getSystemPortPath());
		}
		return result;
	}
}

