package ca.yyx.hu.aap;

import java.util.Locale;

import ca.yyx.hu.usb.UsbAccessoryConnection;
import ca.yyx.hu.utils.AppLog;
import ca.yyx.hu.utils.Utils;


/**
 * @author algavris
 * @date 01/10/2016.
 */

class AapPoll {

    static final byte RESPONSE_MIC_STOP = 1;
    static final byte RESPONSE_MIC_START = 2;
    static final byte RESPONSE_AUDIO_STOP = 3;
    static final byte RESPONSE_AUDIO1_STOP = 4;
    static final byte RESPONSE_AUDIO2_STOP = 5;

    private final UsbAccessoryConnection mConnection;
    private final AapTransport mTransport;

    private byte[] recv_buffer = new byte[Protocol.DEF_BUFFER_LENGTH];
    private byte[] enc_buf = new byte[Protocol.DEF_BUFFER_LENGTH];

    private final AapAudio mAapAudio;
    private final AapMicrophone mAapMicrophone;
    private final AapVideo mAapVideo;
    private final AapControl mAapControl;
    private final ByteArrayPool mByteArrayPool = new ByteArrayPool();

    AapPoll(UsbAccessoryConnection connection, AapTransport transport, AapAudio aapAudio, AapVideo aapVideo) {
        mConnection = connection;
        mTransport = transport;
        mAapAudio = aapAudio;
        mAapVideo = aapVideo;
        mAapMicrophone = new AapMicrophone();
        mAapControl = new AapControl(transport, mAapAudio, mAapMicrophone);
    }

    int poll()
    {
        if (mConnection == null)
        {
            return -1;
        }

        int size = mConnection.recv(recv_buffer, 150);
        if (size <= 0) {
            AppLog.logd("recv %d", size);
            return 0;
        }
        int result = hu_aap_recv_process(size, recv_buffer);
        if (result == 0) {
            result = process_mic();
        }
        mTransport.onPollResult(result);
        return 0;
    }

    private int process_mic() {
        int result = mAapMicrophone.hu_aap_mic_get();
        if (result >= 1) {// && ret <= 2) {                                  // If microphone start (2) or stop (1)...
            return result;                                                   // Done w/ mic notification: start (2) or stop (1)
        }
        // Else if no microphone state change...

        if (mAapAudio.state(Channel.AA_CH_AUD) >= 0)                              // If audio out stop...
            return RESPONSE_AUDIO_STOP;                                                     // Done w/ audio out notification 0
        if (mAapAudio.state(Channel.AA_CH_AU1) >= 0)                              // If audio out stop...
            return RESPONSE_AUDIO1_STOP;                                                     // Done w/ audio out notification 1
        if (mAapAudio.state(Channel.AA_CH_AU2) >= 0)                              // If audio out stop...
            return RESPONSE_AUDIO2_STOP;

        return 0;
    }

    private int hu_aap_recv_process(int msg_len, byte[] msg_buf) {

        int msg_start = 0;
        int have_len = msg_len;                                                   // Length remaining to process for all sub-packets plus 4/8 byte headers

        while (have_len > 0) {

            int chan = (int) msg_buf[msg_start];                                         // Channel
            int flags = msg_buf[msg_start + 1];                                              // Flags

            // Encoded length of bytes to be decrypted (minus 4/8 byte headers)
            int enc_len = Utils.bytesToInt(msg_buf, msg_start + 2, true);

            // Message Type (or post handshake, mostly indicator of SSL encrypted data)
            int msg_type = Utils.bytesToInt(msg_buf, msg_start + 4, true);

            // Length starting at byte 4: Unencrypted Message Type or Encrypted data start
            have_len -= 4;
            // buf points to data to be decrypted
            msg_start += 4;
            if ((flags & 0x08) != 0x08) {
                AppLog.loge("NOT ENCRYPTED !!!!!!!!! have_len: %d  enc_len: %d  buf: %p  chan: %d %s  flags: 0x%02x  msg_type: %d", have_len, enc_len, msg_buf, chan, Channel.name(chan), flags, msg_type);
                return -1;
            }
            if (chan == Channel.AA_CH_VID && flags == 9) {
                // If First fragment Video... (Packet is encrypted so we can't get the real msg_type or check for 0, 0, 0, 1)
                int total_size = Utils.bytesToInt(msg_buf, msg_start, false);

                AppLog.logv("First fragment total_size: %d", total_size);

                have_len -= 4;
                // Remove 4 length bytes inserted into first video fragment
                msg_start += 4;
            }
            int need_len = enc_len - have_len;
            if (need_len > 0) {                                         // If we need more data for the full packet...
                AppLog.loge("have_len: %d < enc_len: %d  need_len: %d", have_len, enc_len, need_len);
                return -1;
            }

            AapMessage msg = iaap_recv_dec_process(chan, flags, msg_start, enc_len, msg_buf);          // Decrypt & Process 1 received encrypted message
            if (msg == null) {                                                    // If error...
                AppLog.loge ("Error iaap_recv_dec_process: have_len: %d enc_len: %d chan: %d %s flags: %01x msg_type: %d", have_len, enc_len, chan, Channel.name(chan), flags, msg_type);
                return -1;
            }

            iaap_msg_process(msg);      // Process decrypted AA protocol message

            have_len -= enc_len;
            msg_start += enc_len;
            if (have_len != 0) {
                AppLog.logd ("iaap_recv_dec_process() more than one message have_len: %d  enc_len: %d", have_len, enc_len);
            }
        }

        return 0;                                                       // Return value from the last iaap_recv_dec_process() call; should be 0
    }

    private AapMessage iaap_recv_dec_process(int chan, int flags, int start, int enc_len, byte[] buf) {// Decrypt & Process 1 received encrypted message

        int bytes_written = mTransport.sslBioWrite(start, enc_len, buf);
        // Write encrypted to SSL input BIO
        if (bytes_written <= 0) {
            AppLog.loge ("BIO_write() bytes_written: %d", bytes_written);
            return null;
        }

        int bytes_read = mTransport.sslRead(enc_buf, enc_buf.length);
        // Read decrypted to decrypted rx buf
        if (bytes_read <= 0) {
            AppLog.loge ("SSL_read bytes_read: %d", bytes_read);
            return null;
        }

        String prefix = String.format(Locale.US, "RECV %d %s %01x", chan, Channel.name(chan), flags);
        AapDump.log(prefix, "AA", chan, flags, enc_buf, enc_len);

        int msg_type = Utils.bytesToInt(enc_buf, 0, true);
        ByteArray ba = mByteArrayPool.obtain(enc_buf, bytes_read);

        return new AapMessage(chan, (byte)flags, msg_type, ba);
    }

    private int iaap_msg_process(AapMessage message) {

        int msg_type = message.type;
        byte flags = message.flags;

        if (message.isAudio() && (msg_type == 0 || msg_type == 1)) {
            return mAapAudio.process(message); // 300 ms @ 48000/sec   samples = 14400     stereo 16 bit results in bytes = 57600
        } else if (message.isVideo() && msg_type == 0 || msg_type == 1 || flags == 8 || flags == 9 || flags == 10) {    // If Video...
            return mAapVideo.process(message);
        } else if ((msg_type >= 0 && msg_type <= 31) || (msg_type >= 32768 && msg_type <= 32799) || (msg_type >= 65504 && msg_type <= 65535)) {
            mAapControl.execute(message);
        } else {
            AppLog.loge ("Unknown msg_type: %d", msg_type);
        }

        return 0;
    }
}
