package com.academicprogram;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import java.io.FileWriter;
import java.io.IOException;

public class AcademicProgram {
    public static void main(String[] args) {
        if (args.length != 6) {
            System.err.println("Usage: AcademicProgram <programName> <semester> <numClassrooms> <numLabs> <facultyIp> <facultyPort>");
            System.exit(1);
        }
        
        String programName = args[0];
        String semester = args[1];
        int numClassrooms = Integer.parseInt(args[2]);
        int numLabs = Integer.parseInt(args[3]);
        String facultyIp = args[4];
        int facultyPort = Integer.parseInt(args[5]);
        
        try (ZContext context = new ZContext()) {
            ZMQ.Socket socket = context.createSocket(SocketType.REQ);
            socket.connect("tcp://" + facultyIp + ":" + facultyPort);
            
            // Build message: format "programName;semester;numClassrooms;numLabs"
            String request = String.join(",", programName, semester, 
                                        String.valueOf(numClassrooms), 
                                        String.valueOf(numLabs));
            
            socket.send(request.getBytes(ZMQ.CHARSET), 0);
            
            // Receive response
            byte[] responseBytes = socket.recv(0);
            String response = new String(responseBytes, ZMQ.CHARSET);
            
            // Save to file: filename based on semester
            String fileName = "response_" + semester + ".txt";
            try (FileWriter writer = new FileWriter(fileName, true)) {
                writer.write("Program: " + programName + " | Response: " + response + "\n");
            } catch (IOException e) {
                System.err.println("Error saving response: " + e.getMessage());
            }
            
            System.out.println("Response received: " + response);
        } catch (NumberFormatException e) {
            System.err.println("Error: numClassrooms and numLabs must be integers.");
        }
    }
}
