package com.mist.connect;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;

/** Diagnostic values only: no raw cause, messages, remote payload or authority. */
public final class VoicePlaybackFailure extends IOException {
    public enum Stage { NORMAL, TRANSPORT_OPEN, HEADER, FRAME, EOS, SINK_START, SINK_WRITE, SINK_HEAD, SOURCE_CLOSE, SINK_ABORT, SINK_CLOSE }
    public enum Category { HTTP, TIMEOUT, EOF, IO, IDENTITY, FRAME, EOS, PCM_LIMIT, DEADLINE, CANCELLED, ALREADY_USED, SINK_START, SINK_WRITE, SINK_HEAD, CLEANUP, OWNER, PLAYBACK, OTHER }
    public final Stage stage;
    public final Category category;
    public final int httpStatus;
    public VoicePlaybackFailure(Stage stage,Category category){this(stage,category,0);}
    public VoicePlaybackFailure(Stage stage,Category category,int status){
        super(category.name());this.stage=stage;this.category=category;
        this.httpStatus=status>=100 && status<=599?status:0;
    }
    public static VoicePlaybackFailure safe(Throwable error,Stage stage){
        if(error instanceof VoicePlaybackFailure)return (VoicePlaybackFailure)error;
        if(error instanceof InterruptedException){
            for(Throwable suppressed:error.getSuppressed())if(suppressed instanceof VoicePlaybackFailure)return (VoicePlaybackFailure)suppressed;
        }
        Category category=error instanceof InterruptedException?Category.CANCELLED:
            error instanceof SocketTimeoutException?Category.TIMEOUT:
            error instanceof EOFException?Category.EOF:
            stage==Stage.SINK_START?Category.SINK_START:stage==Stage.SINK_WRITE?Category.SINK_WRITE:
            stage==Stage.SINK_HEAD?Category.SINK_HEAD:
            stage==Stage.SOURCE_CLOSE || stage==Stage.SINK_ABORT || stage==Stage.SINK_CLOSE?Category.CLEANUP:
            error instanceof IOException?Category.IO:Category.OTHER;
        return new VoicePlaybackFailure(stage,category);
    }
    public String diagnostic(){return "failure_stage="+stage.name()+" category="+category.name()+" http_status="+httpStatus;}
}
