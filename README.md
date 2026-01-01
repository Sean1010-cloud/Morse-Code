# Morse-Code
A project that converts a sentence input from the user into a series of blinks from an ESP32. This used the programming languages of C+ and Java. Some of the code included was given to us, where my group and I added our own code to perform this task.

# Features
- The ESP32 is plugged in through a USB port and is connected to the Java files through the C+ code.
- After the Java main code is run, the user will be prompted to enter a sentence. It will then seperate each individual letter and blink a certain number of times based on each letter, continuing for every letter in the input.

# Process
We started by being given the SerialCommunication, SerialServer, SerialTcpClient, and C+ files which connected the ESP32 to the Java files. 

The Java file is run, where the user enters a sentence. This sentence is then seperated into each individual letter, where each letter is read by the C+ file, and the ESP32 blinks based on the letter. This is done continuously using a loop until the last letter of the sentence is read, forming the morse code blink by reading each individual letter. 

This was done by changing the SerialTcpClient code to add the identification of each possible letter/number/symbol that can be included in the sentence. We then changed the C+ code so that it would be able to receive these identifiers from the Client and blink accordingly. 

# What I Learned
The code given was much more advanced compared to my current knowledge, but gave me the challenge of understanding what the code did and how it functioned according to the C+ code. 

The main idea that I understood from the given code was that SerialCommunication, SerialServer, and SerialTcpClient were used to connect the Java inputs to the C+ code, which allowed the ESP32 to blink based on what was received by the C+ code. 

# Overall Growth
This project allowed me to grow through processing complex code and understand its function. While I may not be able to write and fully understand each line of code, I can understand what the general purpose is. By being met with this kind of code, I will be able to understand complicated code in the future, as well as familiarize myself with the type of code I may have to write.

# Improvements
- Make the identification of each index more efficient
- Add audio output
- Show visual output (e.g in Java terminal)
- Output runtime
- Check for exceptions in user input
