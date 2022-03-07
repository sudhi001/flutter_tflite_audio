package flutter.tflite_audio;

import android.media.MediaRecorder;
import android.media.AudioRecord;
import android.media.AudioFormat;
import android.util.Log;

import java.util.concurrent.locks.ReentrantLock;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.*;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.subjects.PublishSubject;

   
/*  //TODO - ADD or remove re-entract lock????
        recordingBufferLock.lock();
        try {
        } finally {
            recordingBufferLock.unlock();
        }
*/

public class Recording{

    private static final String LOG_TAG = "Recording_Splicer";
 
    private int bufferSize;
    private int audioLength;
    private int sampleRate;
    private int numOfInferences;

    private int inferenceCount = 1;
    private int recordingOffset = 0;
    private int readCount; //recordingOffSet + numberRead - keep count of state
    private int numberRead;
    
    private short[] recordingFrame;
    private short[] recordingBuffer;

    private AudioRecord record;
    private boolean shouldContinue;

    private int remainingLength;
    private int excessLength;
    private short[] remainingFrame;
    private short[] excessFrame;

    private PublishSubject<short []> subject;
    private ReentrantLock recordingBufferLock;

    public Recording(int bufferSize, int audioLength, int sampleRate, int numOfInferences){
        this.bufferSize = bufferSize;
        this.audioLength = audioLength;
        this.sampleRate = sampleRate;
        this.numOfInferences = numOfInferences;

        setParameters();
    }

    public void setReentrantLock(ReentrantLock recordingBufferLock){
        this.recordingBufferLock = recordingBufferLock;
    }

    private void setParameters(){
        subject = PublishSubject.create();

        recordingFrame = new short[bufferSize / 2];
        recordingBuffer = new short[audioLength];

        record = new AudioRecord(
            MediaRecorder.AudioSource.DEFAULT,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize);
    }

    public Observable<short []> getObservable() {
        return (Observable<short []>) this.subject;
     } 


    public void stop(){
        shouldContinue = false;
        record.stop();
        record.release();  
        subject.onComplete();
    }

 
    public void splice(){

        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(LOG_TAG, "Audio Record can't initialize!");
            return;
        }

        startRecording();
        Log.v(LOG_TAG, "Recording started");

        while (shouldContinue) {

            readAudioData();
            recordingBufferLock.lock();

            try {
                switch (getState()) {
                    case "appending":
                        appendData();
                        break;

                    case "recognising":
                        Log.v(LOG_TAG, "recognising");
                        appendData();
                        emitChunk();
                        clearRecordChunk();
                        break;

                    case "finalising":
                        Log.v(LOG_TAG, "finalising");
                        appendData();
                        emitFinalChunk();
                        stop();
                        break;

                    case "trimmingAndRecognising":
                        Log.v(LOG_TAG, "trimming and recognising");
                        calculateExcess();
                        trimExcessToRemain();
                        emitChunk();

                        clearRecordChunk();
                        addExcessToNew();
                        resetExcessCount();
                        break;

                    case "trimmingAndFinalising":
                        Log.v(LOG_TAG, "trimming and finalising");
                        calculateExcess();
                        trimExcessToRemain();
                        emitFinalChunk(); 
                        stop();
                        break;
                    
                    default:
                        Log.v(LOG_TAG, "inferenceCount " + inferenceCount);
                        Log.v(LOG_TAG, "numOfInference " + numOfInferences);
                        Log.v(LOG_TAG, "readCount " + readCount);
                        Log.v(LOG_TAG, "audioLength " + audioLength);
                        throw new AssertionError("Incorrect state when preprocessing");
                }
            } finally {
                recordingBufferLock.unlock();
            }


        }
    }

    private void startRecording(){
        shouldContinue = true;
        record.startRecording();
    }

    private String getState(){
        if (inferenceCount <= numOfInferences && readCount < audioLength) { return "appending"; }
        else if(inferenceCount < numOfInferences && readCount == audioLength){return "recognising"; }
        else if(inferenceCount == numOfInferences && readCount == audioLength){return "finalising";}
        else if(inferenceCount < numOfInferences && readCount > audioLength){ return "trimmingAndRecognising";}
        else if(inferenceCount == numOfInferences && readCount > audioLength){return "trimmingAndFinalising";}
        else{ return "error"; }
    }
    
   
    private void emitChunk(){
        subject.onNext(recordingBuffer);
        inferenceCount += 1;
    }

    private void emitFinalChunk(){
        subject.onNext(recordingBuffer);
    }


    private void readAudioData(){
        numberRead = record.read(recordingFrame, 0, recordingFrame.length);
        readCount = recordingOffset + numberRead; //keep track for conditional
    }

    private void appendData(){
        System.arraycopy(recordingFrame, 0, recordingBuffer, recordingOffset, numberRead);
        recordingOffset += numberRead;

        Log.v(LOG_TAG, "recordingOffset: " + recordingOffset + "/" + audioLength + " | inferenceCount: "
        + inferenceCount + "/" + numOfInferences);
    }


    private void calculateExcess(){
        remainingLength = audioLength - recordingOffset;
        excessLength = readCount - audioLength;

        Log.v(LOG_TAG, "remainingLength: " + remainingLength);
        Log.v(LOG_TAG, "excesslength: " + excessLength);
        remainingFrame = new short[remainingLength];
        excessFrame = new short[excessLength];
    }

    private void trimExcessToRemain(){
        System.arraycopy(recordingFrame, 0, remainingFrame, 0, remainingLength);
        System.arraycopy(remainingFrame, 0, recordingBuffer, recordingOffset, remainingLength);

        recordingOffset += remainingLength;
        Log.v(LOG_TAG, "Excess recording has been trimmed. RecordingOffset now at: " + recordingOffset + "/"
                + audioLength);
    }

    private void addExcessToNew(){
        System.arraycopy(recordingFrame, remainingLength, excessFrame, 0, excessLength);
        System.arraycopy(excessFrame, 0, recordingBuffer, 0, excessLength);
        
        recordingOffset += excessLength;
        Log.v(LOG_TAG, "Added excess length to new recording buffer. RecordingOffset now at: "
                + recordingOffset + "/" + audioLength);
    }

    private void clearRecordChunk(){
        recordingBuffer = new short[audioLength];
        recordingOffset = 0;
    }

    private void resetCount(){
        recordingOffset = 0;
        inferenceCount = 1;
    }

    private void resetExcessCount(){
        remainingLength = 0;
        excessLength = 0;
    }

}