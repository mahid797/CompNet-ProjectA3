/*
 * Author: Jonatan Schroeder
 * Updated: March 2022
 *
 * This code may not be used without written consent of the authors.
 */

package ca.yorku.rtsp.client.net;

import ca.yorku.rtsp.client.exception.RTSPException;
import ca.yorku.rtsp.client.model.Frame;
import ca.yorku.rtsp.client.model.Session;

import java.io.*;
import java.net.DatagramPacket;
import java.net.*;

/**
 * This class represents a connection with an RTSP server.
 */
public class RTSPConnection {

    private static final int BUFFER_LENGTH = 0x10000;

    private Session session;
    private Socket socket;

    private DatagramPacket RTPPacket;
    private DatagramSocket RTP_Socket;

    private BufferedReader RTSPReader;
    private BufferedWriter RTSPWriter;


    private int RTSP_SeqNo;
    private String video;
    private int sessionID;
    private int LOCAL_PORT;

    /**
     * Establishes a new connection with an RTSP server. No message is sent at this point, and no stream is set up.
     *
     * @param session The Session object to be used for connectivity with the UI.
     * @param server  The hostname or IP address of the server.
     * @param port    The TCP port number where the server is listening to.
     * @throws RTSPException If the connection couldn't be accepted, such as if the host name or port number are invalid
     *                       or there is no connectivity.
     */
    public RTSPConnection(Session session, String server, int port) throws RTSPException {

        this.session = session;
        RTSP_SeqNo = 0;

        try {

            socket = new Socket(server, port);
            RTSPReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            RTSPWriter = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));

        } catch (Exception e) {
            throw new RTSPException("Invalid Host");
        }

    }

    /**
     * Sends a SETUP request to the server. This method is responsible for sending the SETUP request, receiving the
     * response and retrieving the session identification to be used in future messages. It is also responsible for
     * establishing an RTP datagram socket to be used for data transmission by the server. The datagram socket should be
     * created with a random UDP port number, and the port number used in that connection has to be sent to the RTSP
     * server for setup. This datagram socket should also be defined to timeout after 1 second if no packet is
     * received.
     *
     * @param videoName The name of the video to be setup.
     * @throws RTSPException If there was an error sending or receiving the RTSP data, or if the RTP socket could not be
     *                       created, or if the server did not return a successful response.
     */
    public synchronized void setup(String videoName) throws RTSPException {

        try {
            RTP_Socket = new DatagramSocket();
            RTP_Socket.setSoTimeout(1000);

        } catch (SocketException e) {
            throw new RTSPException("Timed out");
        }

        RTSP_SeqNo++;

        LOCAL_PORT =  RTP_Socket.getLocalPort();
        this.video = videoName;

        String request_line1 = "SETUP " + video + " RTSP/1.0\n";
        String request_line2 = "CSeq: " + RTSP_SeqNo + "\n";
        String request_line3 = "Transport: RTP/UDP; client_port= " + LOCAL_PORT + "\n";

        try {
            RTSPWriter = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));

            RTSPWriter.write(request_line1);
            RTSPWriter.write(request_line2);
            RTSPWriter.write(request_line3);
            RTSPWriter.write("\n");

            RTSPWriter.flush();

            RTSPResponse rtspResponse = readRTSPResponse();

            if (rtspResponse.getResponseCode() != 200 || !rtspResponse.getResponseMessage().equals("OK")) {
                throw new RTSPException("The server did not return a successful response.");

            } else
                sessionID = Integer.parseInt(rtspResponse.getHeaderValue("Session"));
                //System.out.println("Session: " + sessionID + "\n");

        } catch (RTSPException e){
            throw new RTSPException("Error");

        } catch (IOException e) {
        }

    }

    /**
     * Sends a PLAY request to the server. This method is responsible for sending the request, receiving the response
     * and, in case of a successful response, starting a separate thread responsible for receiving RTP packets with
     * frames.
     *
     * @throws RTSPException If there was an error sending or receiving the RTSP data, or if the server did not return a
     *                       successful response.
     */
    public synchronized void play() throws RTSPException {
        RTSP_SeqNo++;

        String request_line1 = "PLAY " + video + " RTSP/1.0\n";
        String request_line2 = "CSeq: " + RTSP_SeqNo + "\n";
        String request_line3 = "Session: " + sessionID + "\n";

        try {

            RTSPWriter.write(request_line1);
            RTSPWriter.write(request_line2);
            RTSPWriter.write(request_line3);
            RTSPWriter.write("\n");

            RTSPWriter.flush();

            RTSPResponse rtspResponse = readRTSPResponse();

            if(rtspResponse.getResponseCode()!=200||!rtspResponse.getResponseMessage().equals("OK")){
                throw new RTSPException("The server did not return a successful response.");
            }

        } catch (IOException e1) {
            throw new RTSPException(e1);

        }
        new RTPReceivingThread().start();
    }


    private class RTPReceivingThread extends Thread {
        /**
         * Continuously receives RTP packets until the thread is cancelled. Each packet received from the datagram
         * socket is assumed to be no larger than BUFFER_LENGTH bytes. This data is then parsed into a Frame object
         * (using the parseRTPPacket method) and the method session.processReceivedFrame is called with the resulting
         * packet. The receiving process should be configured to timeout if no RTP packet is received after two seconds.
         */
        @Override
        public void run() {

            byte[] buffer = new byte[BUFFER_LENGTH];
            RTPPacket = new DatagramPacket(buffer, buffer.length);

            try {
                while (true){
                    RTP_Socket.receive(RTPPacket);
                    RTP_Socket.setSoTimeout(2000);
                    session.processReceivedFrame(parseRTPPacket(RTPPacket));
                }
            } catch (Exception e) {
                //
            }


        }

    }

    /**
     * Sends a PAUSE request to the server. This method is responsible for sending the request, receiving the response
     * and, in case of a successful response, stopping the thread responsible for receiving RTP packets with frames.
     *
     * @throws RTSPException If there was an error sending or receiving the RTSP data, or if the server did not return a
     *                       successful response.
     */
    public synchronized void pause() throws RTSPException {
        RTSP_SeqNo++;
        String request_line1 = "PAUSE " + video + " RTSP/1.0\n";
        String request_line2 = "CSeq: " + RTSP_SeqNo + "\n";
        String request_line3 = "Session: " + sessionID + "\n";

        try {

            RTSPWriter.write(request_line1);
            RTSPWriter.write(request_line2);
            RTSPWriter.write(request_line3);
            RTSPWriter.write("\n");

            RTSPWriter.flush();

            RTSPResponse rtspResponse = readRTSPResponse();

            if(rtspResponse.getResponseCode()!=200||!rtspResponse.getResponseMessage().equals("OK")){
                throw new RTSPException("The server did not return a successful response.");
            }

            new RTPReceivingThread().interrupt();

        } catch (IOException e1) {
            throw new RTSPException(e1);

        }
    }

    /**
     * Sends a TEARDOWN request to the server. This method is responsible for sending the request, receiving the
     * response and, in case of a successful response, closing the RTP socket. This method does not close the RTSP
     * connection, and a further SETUP in the same connection should be accepted. Also this method can be called both
     * for a paused and for a playing stream, so the thread responsible for receiving RTP packets will also be
     * cancelled.
     * movie1.Mjpeg
     * @throws RTSPException If there was an error sending or receiving the RTSP data, or if the server did not return a
     *                       successful response.
     */
    public synchronized void teardown() throws RTSPException {

        RTSP_SeqNo++;
        String request_line1 = "TEARDOWN " + video + " RTSP/1.0\n";
        String request_line2 = "CSeq: " + RTSP_SeqNo + "\n";
        String request_line3 = "Session: " + sessionID + "\n";

        try {

            RTSPWriter.write(request_line1);
            RTSPWriter.write(request_line2);
            RTSPWriter.write(request_line3);
            RTSPWriter.write("\n");

            RTSPWriter.flush();

            RTSPResponse rtspResponse = readRTSPResponse();

            if(rtspResponse.getResponseCode()!=200||!rtspResponse.getResponseMessage().equals("OK")){
                throw new RTSPException("The server did not return a successful response.");
            }

            RTP_Socket.close();
            new RTPReceivingThread().interrupt();

        } catch (IOException e1) {
            throw new RTSPException(e1);

        }

    }

    /**
     * Closes the connection with the RTSP server. This method should also close any open resource associated to this
     * connection, such as the RTP connection, if it is still open.
     */
    public synchronized void closeConnection() {

        try {
            this.RTSPReader.close();
            this.RTSPWriter.close();
            socket.close();

        } catch (Exception e) {
            //System.out.println(e);
        }
    }


    /**
     * Parses an RTP packet into a Frame object.
     *
     * @param packet the byte representation of a frame, corresponding to the RTP packet.
     * @return A Frame object.
     */
    public static Frame parseRTPPacket(DatagramPacket packet) {
        /*short tempByte = packet.getData()[1];
        boolean marker =(tempByte&(1<<0))!=0;
        int pt1 = (packet.getData()[1]&0b1111110000);
        byte pt = (byte) (pt1 >>> 4);
        short sn = (short) ((packet.getData()[2]<<8) | (packet.getData()[3]));
        int ts = (packet.getData()[4] << 24 | (packet.getData()[5] & 0xFF) << 16 | (packet.getData()[6] & 0xFF) << 8 | (packet.getData()[7] & 0xFF));*/

        int tempByte = packet.getData()[1];
        boolean marker =(tempByte&(1<<0))!=0;
        byte payload_type = (byte) (packet.getData()[1] & 0x0fffffff);
        short sequence_number = (short) ((packet.getData()[2]<<8) | (packet.getData()[3]));
        int time_stamp = (packet.getData()[4] << 24 | (packet.getData()[5] & 0xFF) << 16 | (packet.getData()[6] & 0xFF) << 8 | (packet.getData()[7] & 0xFF));

        System.out.println("test = " + Long.toString(packet.getData()[1],2));
        //System.out.println("test0 = " + Integer.toString(pt1,2));
        System.out.println("test1 = " + Long.toString(payload_type&0x0fffffff,2));
        System.out.println("test2 = " + (packet.getData()[1]&0x0fffffff));

        Frame frame = new Frame(payload_type, marker, sequence_number, time_stamp, packet.getData(), 12, packet.getLength()-12);
        System.out.println("test3 = " + frame.getPayloadType());
        return frame;
    }

    /**
     * Reads and parses an RTSP response from the socket's input.
     *
     * @return An RTSPResponse object if the response was read completely, or null if the end of the stream was reached.
     * @throws IOException   In case of an I/O error, such as loss of connectivity.
     * @throws RTSPException If the response doesn't match the expected format.
     */
    public RTSPResponse readRTSPResponse() throws IOException, RTSPException {

        String first = RTSPReader.readLine();
        if (first == null)
            return null;
        String[] firstSplit = first.split(" ", 3);

        if (firstSplit.length != 3
                || !"RTSP/1.0".equalsIgnoreCase(firstSplit[0]))
            throw new RTSPException("Invalid response from RTSP server.");

        RTSPResponse rtspResponse = new RTSPResponse(firstSplit[0], Integer.parseInt(firstSplit[1]), firstSplit[2]);

        int cSeqNo = 0;
        int sessID = 0;
        String header;

        while ((header = RTSPReader.readLine()) != null
                && !header.equals("")) {

            String[] headerSplit = header.split(":", 2);
            if (headerSplit.length != 2)
                continue;

            if (headerSplit[0].equalsIgnoreCase("CSeq"))
                cSeqNo = Integer.parseInt(headerSplit[1].trim());

            else if (headerSplit[0].equalsIgnoreCase("Session"))
                sessID = Integer.parseInt(headerSplit[1].trim());

        }

        //System.out.println("CSEQ" + RTSPSeqNo);
        rtspResponse.addHeaderValue("CSeq",String.valueOf(cSeqNo));
        rtspResponse.addHeaderValue("Session",String.valueOf(sessID));

        return rtspResponse;
    }


}
