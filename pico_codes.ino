///////////////////////////////////////////////////////////////////////

#define PICO_WIRELESS_BOARD  //<========= COMMENT ME IF YOU ARE *NOT* ON PICO WIRELESS BOARDS!!

///////////////////////////////////////////////////////////////////////
// This can go on a separate header if you like ...
#ifndef NAMO_SERIAL_PROTO_H
#define NAMO_SERIAL_PROTO_H

#include <Arduino.h>
#include <stdint.h>
#include <stdbool.h>
#include <string.h>

namespace namo {
	
	class SerialProtocol {
		public:
		// Message types
		static const uint8_t MSG_TYPE_TEXT   = 0;
		static const uint8_t MSG_TYPE_BINARY = 1;
		
		// Maximum sizes
		static const uint32_t MAX_KEY_SIZE   = 256;
		static const uint32_t MAX_VALUE_SIZE = 2048;
		static const uint8_t  HEADER_SIZE    = 8;
		
		// Callback function type for received messages
		typedef void (*MessageCallback)(const char* key, const uint8_t* value, uint32_t valueLen, uint8_t msgType);
		
		private:
		uint8_t headerBuffer[HEADER_SIZE];
		uint8_t keyBuffer[MAX_KEY_SIZE + 1];   
		uint8_t valueBuffer[MAX_VALUE_SIZE + 1];
		MessageCallback messageCallback;
		
		static uint32_t convertBytesToUint32(const uint8_t* bytes) {
			return ((uint32_t)bytes[0] << 24) |
			((uint32_t)bytes[1] << 16) |
			((uint32_t)bytes[2] <<  8) |
			(uint32_t)bytes[3];
		}
		
		static void convertUint32ToBytes(uint32_t value, uint8_t* bytes) {
			bytes[0] = (value >> 24) & 0xFF;
			bytes[1] = (value >> 16) & 0xFF;
			bytes[2] = (value >>  8) & 0xFF;
			bytes[3] =  value        & 0xFF;
		}
		
		bool readExactly(uint8_t* buffer, size_t length) {
			size_t bytesRead = 0;
			unsigned long timeout = millis() + 1000;
			
			while (bytesRead < length) {
				if (Serial.available() > 0) {
					buffer[bytesRead] = Serial.read();
					bytesRead++;
					timeout = millis() + 1000;
				}
				else if (millis() > timeout) {
					return false;
				}
				yield();
			}
			return true;
		}
		
		bool writeMessage(const char* key, const uint8_t* value, 
		uint32_t valueLength, uint8_t type) 
		{
			uint32_t keyLength = strlen(key);
			uint8_t lenBytes[4];
			
			if (keyLength > MAX_KEY_SIZE || valueLength > MAX_VALUE_SIZE) {
				return false;
			}
			
			// Write header (key length + value length)
			convertUint32ToBytes(keyLength, lenBytes);
			Serial.write(lenBytes, 4);
			
			convertUint32ToBytes(valueLength, lenBytes);
			Serial.write(lenBytes, 4);
			
			// Write payload
			Serial.write((const uint8_t*)key, keyLength);
			Serial.write(value, valueLength);
			Serial.write(type);
			
			Serial.flush();
			return true;
		}
		
		public:
		SerialProtocol() : messageCallback(nullptr) {}
		
		void begin(MessageCallback callback) {
			messageCallback = callback;
		}
		
		bool sendText(const char* key, const char* value) {
			return writeMessage(key, (const uint8_t*)value, strlen(value), MSG_TYPE_TEXT);
		}
		
		bool sendBinary(const char* key, const uint8_t* value, uint32_t valueLen) {
			return writeMessage(key, value, valueLen, MSG_TYPE_BINARY);
		}
		
		void process() {
			if (Serial.available() > 0) {
				// Read and validate header
				if (!readExactly(headerBuffer, HEADER_SIZE)) {
					while (Serial.available()) Serial.read();
					return;
				}
				
				uint32_t keyLength = convertBytesToUint32(headerBuffer);
				uint32_t valueLength = convertBytesToUint32(headerBuffer + 4);
				
				// Validate sizes
				if (keyLength == 0 || keyLength > MAX_KEY_SIZE ||
				valueLength == 0 || valueLength > MAX_VALUE_SIZE) 
				{
					while (Serial.available()) Serial.read();
					return;
				}
				
				// Read key
				if (!readExactly(keyBuffer, keyLength)) {
					while (Serial.available()) Serial.read();
					return;
				}
				keyBuffer[keyLength] = '\0';
				
				// Read value
				if (!readExactly(valueBuffer, valueLength)) {
					while (Serial.available()) Serial.read();
					return;
				}
				valueBuffer[valueLength] = '\0';
				
				// Read type
				uint8_t messageType;
				if (!readExactly(&messageType, 1)) {
					while (Serial.available()) Serial.read();
					return;
				}
				
				// Call callback if registered
				if (messageCallback) {
					messageCallback((char*)keyBuffer, valueBuffer, valueLength, messageType);
				}
			}
		}
	};
	
} // namespace namo

#endif // NAMO_SERIAL_PROTO_H
///////////////////////////////////////////////////////////////////////


///////////////////////////////////////////////////////////////////////
// And here is our main code ...
#include <Arduino.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "pico.h"
#include "pico/stdlib.h"
#include "pico/printf.h"
#include "pico/double.h"
#include "pico/multicore.h"
#include "pico/platform/cpu_regs.h"
#include "hardware/timer.h"
#include "hardware/irq.h"

#ifdef PICO_WIRELESS_BOARD
  #include "cyw43.h"
  #include "pico/cyw43_arch.h"
#endif

//=============================================================================
// Pin Definitions and Constants
//=============================================================================
// LED Pin Definitions for PICO and PICO-W Boards
#define LED_PIN_PICO_BOARD 25  // Onboard LED pin for standard Pico (GP25)
#define LED_PIN_PICOW_BOARD 0  // LED pin for Pico W (GPIO0 on CYW43 chip)
//=============================================================================


// Global Variables
//=============================================================================
// Our NAMO Serial Protocol instance
namo::SerialProtocol serialProto;
volatile bool core0_led_state = false;  // Tracks LED state for core 0
#ifdef PICO_WIRELESS_BOARD
  volatile bool core1_led_state = false;  // Tracks LED state for core 1 (Wireless LED)
#endif

//=============================================================================
// Core 0 Functions (Main core)
//=============================================================================
bool toggleCore0LED() {
  core0_led_state = !core0_led_state;                              // Toggle LED state
  digitalWrite(LED_PIN_PICO_BOARD, core0_led_state ? HIGH : LOW);  // Update LED state
  return core0_led_state;                                                     
}

/**
 * @brief Initialize core 0 peripherals and timer 
 */
void setup() {
  Serial.begin(9600);
  while (!Serial) delay(10);  
  
  // Configure onboard LED pin
  pinMode(LED_PIN_PICO_BOARD, OUTPUT);  
  toggleCore0LED();
  delay(500);
  toggleCore0LED();

	serialProto.begin(onMessageReceived);
}

/**
* @brief Main loop for core 0
*/
void loop() {
  serialProto.process();
}

//=============================================================================
// Core 1 Functions (Secondary core)
//=============================================================================
#ifndef PICO_WIRELESS_BOARD
  bool toggleCore1LED() { return true;}
#endif

#ifdef PICO_WIRELESS_BOARD
/**
* @brief Timer callback function for toggling the wireless chip LED *
*/
bool toggleCore1LED() {
  core1_led_state = !core1_led_state;                         // Toggle LED state
  cyw43_arch_gpio_put(LED_PIN_PICOW_BOARD, core1_led_state);  // Update LED state
  return core1_led_state;                                                
}
/**
* @brief Initialize core 1 peripherals and timer */
void setup1() {
  // Initialize wireless chip
  if (cyw43_arch_init()) {
    while (true) {
      tight_loop_contents();  // Infinite loop if initialization fails
    }
  }

  toggleCore1LED();
  delay(500);
  toggleCore1LED();
}

/**
* @brief Main loop for core 1
*
* Simulates background tasks while wireless LED blinking is handled by the timer. */
void loop1() {
  // Simulate background tasks
  delay(10000);
}
#endif


void processMsg(const char* key, const uint8_t* value, uint32_t valueLen, uint8_t msgType)
{
	//strncmp(key, LED_TOGGLE_KEY, strlen(LED_TOGGLE_KEY)) == 0)
	//if (msgType == namo::SerialProtocol::MSG_TYPE_BINARY && key[0] == 'L' && key[1] == 'E')
	if (msgType == namo::SerialProtocol::MSG_TYPE_BINARY && strncmp(key, "LED_TOGGLE", 8) == 0)
	{
		int waitVal = 10 * value[1];
		if (valueLen > 0) {  // Make sure we have data
			for(uint8_t i = 0; i < value[0] && i < 255; i++) { 
				toggleCore0LED();
        toggleCore1LED();
				delay(waitVal);
				toggleCore0LED();
        toggleCore1LED();
				delay(waitVal);
			}
		}
	}
}

void onMessageReceived(const char* key, const uint8_t* value, uint32_t valueLen, uint8_t msgType) 
{
	// Process message first
	processMsg(key, value, valueLen, msgType);
	
	// Then send acknowledgment
	char newKey[namo::SerialProtocol::MAX_KEY_SIZE];
	snprintf(newKey, sizeof(newKey), "%s_ack", key);
	
	if (msgType == namo::SerialProtocol::MSG_TYPE_TEXT) {
		char newValue[namo::SerialProtocol::MAX_VALUE_SIZE];
		snprintf(newValue, sizeof(newValue), "%s_ack", (char*)value);
		serialProto.sendText(newKey, newValue);
	}
	else if (msgType == namo::SerialProtocol::MSG_TYPE_BINARY) {
		serialProto.sendBinary(newKey, value, valueLen);
	}
}
