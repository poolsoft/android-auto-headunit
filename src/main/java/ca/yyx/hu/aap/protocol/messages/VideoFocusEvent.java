package ca.yyx.hu.aap.protocol.messages;

import com.google.protobuf.nano.MessageNano;

import ca.yyx.hu.aap.AapMessage;
import ca.yyx.hu.aap.protocol.Channel;
import ca.yyx.hu.aap.protocol.MsgType;
import ca.yyx.hu.aap.protocol.nano.Protocol;

/**
 * @author algavris
 * @date 13/02/2017.
 */

public class VideoFocusEvent extends AapMessage {

    private static MessageNano makeProto(int mode, boolean unsolicited)
    {
        Protocol.VideoFocusNotification videoFocus = new Protocol.VideoFocusNotification();
        videoFocus.mode = mode;
        videoFocus.unsolicited = unsolicited;

        return videoFocus;
    }

    public VideoFocusEvent(int mode, boolean unsolicited)
    {
        super(Channel.ID_VID, MsgType.Media.VIDEOFOCUSNOTIFICATION, makeProto(mode, unsolicited));
    }
}