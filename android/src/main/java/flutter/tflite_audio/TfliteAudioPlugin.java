package flutter.tflite_audio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.res.AssetFileDescriptor;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.media.AudioRecord;
import android.media.AudioFormat;
import android.media.MediaRecorder;
import android.os.Looper;
import android.os.Handler;

// import android.media.MediaPlayer; //testing purpose


import androidx.core.app.ActivityCompat;
import androidx.annotation.NonNull;

import org.tensorflow.lite.Interpreter;

import java.util.concurrent.CompletableFuture; //required to get value from thread
import java.util.concurrent.CountDownLatch;
import java.io.BufferedReader;
import java.io.BufferedInputStream; //required for preprocessing
import java.io.ByteArrayOutputStream; //required for preprocessing
import java.io.DataOutputStream; //required for preprocessing
import java.io.InputStream; //required for preprocessing
import java.io.ObjectOutputStream; //required for preprocessing 
import java.io.InputStreamReader; 
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Date;

import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.embedding.engine.plugins.FlutterPlugin;

import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.view.FlutterMain;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry; //required for onRequestPermissionsResult
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.EventChannel.EventSink;
import io.flutter.plugin.common.EventChannel.StreamHandler;


public class TfliteAudioPlugin implements MethodCallHandler, StreamHandler, FlutterPlugin, ActivityAware, PluginRegistry.RequestPermissionsResultListener {

    //ui elements
    private static final String LOG_TAG = "Tflite_audio";
    private static final int REQUEST_RECORD_AUDIO = 13;
    private static final int REQUEST_READ_EXTERNAL_STORAGE = 1;
    private static TfliteAudioPlugin instance;
    private Handler handler = new Handler(Looper.getMainLooper());

    //working recording variables
    AudioRecord record;
    short[] recordingBuffer;
    short[] recordingBufferCache;
    int countNumOfInferences = 1;
    int recordingOffset = 0;
    boolean shouldContinue = true;
    private Thread recordingThread;
    private final ReentrantLock recordingBufferLock = new ReentrantLock();

    //preprocessing variables
    private Thread preprocessThread;
    private String audioDir;

    //working label variables
    private List<String> labels;

    //working recognition variables
    boolean lastInferenceRun = false;
    private long lastProcessingTimeMs;
    private Thread recognitionThread;
    private Interpreter tfLite;
    private LabelSmoothing labelSmoothing = null;

    //flutter
    private AssetManager assetManager;
    private Activity activity;
    private Context applicationContext;
    private MethodChannel methodChannel;
    private EventChannel eventChannel;
    private EventSink events;

    //recording variables
    private int bufferSize;
    private int sampleRate;
    private int recordingLength;
    private int numOfInferences;

    //Determine input and output
    private String inputType;
    private boolean outputRawScores;

    // get objects to convert to float and long
    private double detectObj;
    private int avgWinObj;
    private int minTimeObj;
    
    //labelsmoothing variables 
    private float detectionThreshold;
    private long averageWindowDuration;
    private long minimumTimeBetweenSamples;
    private int suppressionTime;

    static Activity getActivity() {
        return instance.activity;
      }
    
      public TfliteAudioPlugin() {
        instance = this;
      }

    @Override
    public void onAttachedToEngine(FlutterPluginBinding binding) {
      onAttachedToEngine(binding.getApplicationContext(), binding.getBinaryMessenger());
    }

    private void onAttachedToEngine(Context applicationContext, BinaryMessenger messenger) {
        this.applicationContext = applicationContext;
        this.assetManager = applicationContext.getAssets();

        this.methodChannel = new MethodChannel(messenger, "tflite_audio");
        this.methodChannel.setMethodCallHandler(this);

        this.eventChannel = new EventChannel(messenger, "startAudioRecognition");
        this.eventChannel.setStreamHandler(this);

    }

    @Override
    public void onDetachedFromEngine(FlutterPluginBinding binding) {
        this.applicationContext = null;
        this.assetManager = null;

        this.methodChannel.setMethodCallHandler(null);
        this.methodChannel = null;

        this.eventChannel.setStreamHandler(null);
        this.eventChannel = null;
    }


    public void onAttachedToActivity(ActivityPluginBinding binding) {
        // onAttachedToActivity(binding.getActivity());
        this.activity = binding.getActivity();
        binding.addRequestPermissionsResultListener(this);
    }

    // @Override
    public void onDetachedFromActivityForConfigChanges() {
      this.activity = null;
    }
  
    // @Override
    public void onReattachedToActivityForConfigChanges(ActivityPluginBinding activityPluginBinding) {
      this.activity = activityPluginBinding.getActivity();
      activityPluginBinding.addRequestPermissionsResultListener(this);
    }

    // @Override
    public void onDetachedFromActivity() {
      this.activity = null;
    }


    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result _result) {
        HashMap arguments = (HashMap) call.arguments;
        Result result = _result;

        switch (call.method) {
            case "loadModel":
                Log.d(LOG_TAG, "loadModel");
                this.inputType = (String) arguments.get("inputType");
                this.outputRawScores = (boolean) arguments.get("outputRawScores");
                try {
                    loadModel(arguments);
                } catch (Exception e) {
                    result.error("failed to load model", e.getMessage(), e);
                }
                break;
            case "stopAudioRecognition":
                forceStopRecogniton();
                break;
            case "recogniseAudioFile":
                this.audioDir = (String) arguments.get("audioDirectory");
                checkPermissions(REQUEST_READ_EXTERNAL_STORAGE);
                break;
            default:
                result.notImplemented();
                break;
        }
    }


    @Override
    public void onListen(Object _arguments, EventSink events) {
        HashMap arguments = (HashMap) _arguments;

        this.events = events;

        //load recording variables
        this.bufferSize = (int) arguments.get("bufferSize");
        this.sampleRate = (int) arguments.get("sampleRate");
        this.recordingLength = (int) arguments.get("recordingLength");
        this.numOfInferences = (int) arguments.get("numOfInferences");

        // get objects to convert to float and long
        this.detectObj = (double) arguments.get("detectionThreshold");
        this.avgWinObj = (int) arguments.get("averageWindowDuration");
        this.minTimeObj = (int) arguments.get("minimumTimeBetweenSamples");
        
        //load labelsmoothing variables 
        this.detectionThreshold = (float)detectObj;
        this.averageWindowDuration = (long)avgWinObj;
        this.minimumTimeBetweenSamples = (long)minTimeObj;
        this.suppressionTime = (int) arguments.get("suppressionTime");

        checkPermissions(REQUEST_RECORD_AUDIO);
    }

    @Override
    public void onCancel(Object arguments) {
        this.events = null;
    }


    private void loadModel(HashMap arguments) throws IOException {
        String model = arguments.get("model").toString();
        Log.d(LOG_TAG, "model name is: " + model);
        Object isAssetObj = arguments.get("isAsset");
        boolean isAsset = isAssetObj == null ? false : (boolean) isAssetObj;
        MappedByteBuffer buffer = null;
        String key = null;
        if (isAsset) {
            key = FlutterMain.getLookupKeyForAsset(model);
            AssetFileDescriptor fileDescriptor = assetManager.openFd(key);
            FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
            FileChannel fileChannel = inputStream.getChannel();
            long startOffset = fileDescriptor.getStartOffset();
            long declaredLength = fileDescriptor.getDeclaredLength();
            buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        } else {
            FileInputStream inputStream = new FileInputStream(new File(model));
            FileChannel fileChannel = inputStream.getChannel();
            long declaredLength = fileChannel.size();
            buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, declaredLength);
        }

        int numThreads = (int) arguments.get("numThreads");
        final Interpreter.Options tfliteOptions = new Interpreter.Options();
        tfliteOptions.setNumThreads(numThreads);
        tfLite = new Interpreter(buffer, tfliteOptions);

        //load labels
        String labels = arguments.get("label").toString();
        Log.d(LOG_TAG, "label name is: " + labels);

        if (labels.length() > 0) {
            if (isAsset) {
                key = FlutterMain.getLookupKeyForAsset(labels);
                loadLabels(assetManager, key);
            } else {
                loadLabels(null, labels);
            }
        }

    }

    private void loadLabels(AssetManager assetManager, String path) {
        BufferedReader br;
        try {
            if (assetManager != null) {
                br = new BufferedReader(new InputStreamReader(assetManager.open(path)));
            } else {
                br = new BufferedReader(new InputStreamReader(new FileInputStream(new File(path))));
            }
            String line;
            labels = new ArrayList<>(); //resets label input
            while ((line = br.readLine()) != null) {
                labels.add(line);
            }
            Log.d(LOG_TAG, "Labels: " + labels.toString());
            br.close();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read label file", e);
        }

    }


    private void checkPermissions(int permissionType) {
        Log.d(LOG_TAG, "Check for permission. Request code: " + permissionType);

        PackageManager pm = applicationContext.getPackageManager();

        switch(permissionType){
            case REQUEST_RECORD_AUDIO:
                int recordPerm = pm.checkPermission(Manifest.permission.RECORD_AUDIO, applicationContext.getPackageName());
                boolean hasRecordPerm = recordPerm == PackageManager.PERMISSION_GRANTED;

                if (hasRecordPerm) {
                    startRecording();
                    Log.d(LOG_TAG, "Permission already granted. start recording");
                } else {
                    requestPermission(REQUEST_RECORD_AUDIO);
                }
                break;

            case REQUEST_READ_EXTERNAL_STORAGE:
                int readPerm = pm.checkPermission(Manifest.permission.READ_EXTERNAL_STORAGE, applicationContext.getPackageName());
                boolean hasReadPerm = readPerm == PackageManager.PERMISSION_GRANTED;
                if (hasReadPerm) {
                    loadAudioFile();
                    Log.d(LOG_TAG, "Permission already granted. Loading audio file..");
                } else {
                    requestPermission(REQUEST_READ_EXTERNAL_STORAGE); 
                }
                break;
            default:
                Log.d(LOG_TAG, "Something weird has happened");
                
        }

        //Add run time error here for other permissions?
        
  
    }

    private void requestPermission(int permissionType) {
        Log.d(LOG_TAG, "Permission requested.");
        Activity activity = TfliteAudioPlugin.getActivity();

        switch(permissionType){
            case REQUEST_RECORD_AUDIO:
                ActivityCompat.requestPermissions(activity,
                        new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
                break;
            case REQUEST_READ_EXTERNAL_STORAGE:
                ActivityCompat.requestPermissions(activity,
                        new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_READ_EXTERNAL_STORAGE);
                break;
            default:
                Log.d(LOG_TAG, "Something weird has happened");
            }
    }

    // @Override
    public boolean onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case REQUEST_RECORD_AUDIO:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED){
                    startRecording();
                    Log.d(LOG_TAG, "Permission granted. Start recording...");
                }else{
                    showRationaleDialog(
                        "Microphone Permissions",
                        "Permission has been declined. Please accept permissions in your settings"
                    );
                    if (events != null) {
                        events.endOfStream();
                    }
                }
                break;
            case REQUEST_READ_EXTERNAL_STORAGE:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED){
                    loadAudioFile();
                    Log.d(LOG_TAG, "Permission granted. Loading audio file...");
                }else{
                    showRationaleDialog(
                        "Read External Storage Permissions",
                        "Permission has been declined. Please accept permissions in your settings"
                    );
                    if (events != null) {
                        events.endOfStream();
                    }
                }
                break;
            default:
            Log.d(LOG_TAG, "onRequestPermissionsResult: default error...");
                break;
        }
        //placehold value 
        return true;
    }


    public void showRationaleDialog(String title, String message) {

        runOnUIThread(() -> {
            Activity activity = TfliteAudioPlugin.getActivity();
            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setTitle(title);
            builder.setMessage(message);
            builder.setPositiveButton(
                    "Settings",
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + activity.getPackageName()));
                            intent.addCategory(Intent.CATEGORY_DEFAULT);
                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            activity.startActivity(intent);
                        }
                    });
            builder.setNegativeButton(
                    "Cancel",
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.cancel();
                        }
                    });
            AlertDialog alert = builder.create();
            alert.show();
        });

    }

    public synchronized void loadAudioFile() {
        if (preprocessThread != null) {
            return;
        }
        shouldContinue = true;
        preprocessThread =
                new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                preprocessAudioFile();
                            }
                        });
        preprocessThread.start();
    }

    private void preprocessAudioFile(){
        Log.d(LOG_TAG, "Preprocessing audio file..");
        try {
            String key = FlutterMain.getLookupKeyForAsset(audioDir);
            AssetFileDescriptor fileDescriptor = assetManager.openFd(key);
            FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());

            FileChannel fileChannel = inputStream.getChannel();
            long startOffset = fileDescriptor.getStartOffset();
            long declaredLength = fileDescriptor.getDeclaredLength();
            MappedByteBuffer buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);

            int fileSize = buffer.limit();
            int bufferSize = 44032;
            int indexCount = 0;
            int inferenceCount = 0;
            int numOfInferences = (int) Math.ceil((float) fileSize/bufferSize);
            Log.d(LOG_TAG, "fileSize: " + fileSize/1000 + " KB");
            Log.d(LOG_TAG, "numOfInference " + numOfInferences);

            byte[] buf = new byte[bufferSize];
            byte[] tempBuf = new byte[bufferSize];
            for (int i = 0; i < fileSize; i++){   
                
                //Inferences that is not final
                if((i+1) % bufferSize == 0 && inferenceCount != numOfInferences){
                    // Add recognize here
                    Log.d(LOG_TAG, "Making inference and clearing buffer array");
                    Log.d(LOG_TAG, "Audio file1: " + Arrays.toString(buf));
                    buf = new byte[bufferSize];

                    //need to reset index or out of array error
                    buf[indexCount] = buffer.get(i);
                    Log.d(LOG_TAG, "Index: " + i);
                    Log.d(LOG_TAG, "IndexCount: " + indexCount);
                    indexCount = 0; 
                    inferenceCount += 1;
                
                //Final inference 
                }else if(i == fileSize-1 && inferenceCount == numOfInferences-1){
                        Log.d(LOG_TAG, "Making final inference.");
                        Log.d(LOG_TAG, "Audio file2: " + Arrays.toString(buf));
                        Log.d(LOG_TAG, "Index: " + i);
                        Log.d(LOG_TAG, "IndexCount: " + indexCount);
                        buf = new byte[bufferSize];
                        break;  //break in case buffer.limit() does not stop the loop
                
                //append mappedbytebuffer to inference buffer
                }else{
                    buf[indexCount] = buffer.get(i);
                    //for debugging
                    // if(inferenceCount == numOfInferences-1){
                    //     Log.d(LOG_TAG, "Index: " + i);
                    //     Log.d(LOG_TAG, "IndexCount: " + indexCount);
                    // }
                    indexCount += 1;
                }
            }
            
          } catch(IOException e) {
            Log.d(LOG_TAG, "Error loading audio file: " + e);
          }

    }

   
    public synchronized void startRecording() {
        if (recordingThread != null) {
            return;
        }
        shouldContinue = true;
        recordingThread =
                new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                record();
                            }
                        });
        recordingThread.start();
    }

    private void record() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);

   
        short[] recordingFrame = new short[bufferSize / 2];
        recordingBuffer = new short[recordingLength]; //this buffer will be fed into model
        recordingBufferCache = new short[recordingLength]; //temporary holds recording buffer until recognitionStarts

        // Estimate the buffer size we'll need for this device.
        // int bufferSize =
        //         AudioRecord.getMinBufferSize(
        //                 sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        // if (bufferSize == AudioRecord.ERROR || bufferSize == Audi oRecord.ERROR_BAD_VALUE) {
        //     bufferSize = sampleRate * 2;
        // }
        // Log.v(LOG_TAG, "Buffer size: " + bufferSize);

        record =
                new AudioRecord(
                        MediaRecorder.AudioSource.DEFAULT,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize);

        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(LOG_TAG, "Audio Record can't initialize!");
            return;
        }

        record.startRecording();

        Log.v(LOG_TAG, "Recording started");


        while (shouldContinue) {
            //Reads audio data and records it into redcordFrame
            int numberRead = record.read(recordingFrame, 0, recordingFrame.length);
            int recordingOffsetCount = recordingOffset + numberRead;
            // Log.v(LOG_TAG, "recordingOffsetCount: " + recordingOffsetCount);

            recordingBufferLock.lock();
            try {
                
                //Continue to append frame until it reaches recording length
                if(countNumOfInferences <= numOfInferences && recordingOffsetCount < recordingLength){
    
                    System.arraycopy(recordingFrame, 0, recordingBufferCache, recordingOffset, numberRead);
                    recordingOffset += numberRead;
                    Log.v(LOG_TAG, "recordingOffset: " + recordingOffset + "/" + recordingLength + " inferenceCount: " + countNumOfInferences);
                
                //When recording buffer populates, inference starts. Resets recording buffer after iference
                }else if(countNumOfInferences < numOfInferences  && recordingOffsetCount == recordingLength){
                 
          
                    Log.v(LOG_TAG, "Recording reached threshold");
                    System.arraycopy(recordingFrame, 0, recordingBufferCache, recordingOffset, numberRead);
                    recordingOffset += numberRead;
               
                    Log.v(LOG_TAG, "recordingOffset: " + recordingOffset + "/" + recordingLength);  
                    recordingBuffer = recordingBufferCache;
                    startRecognition();

                    Log.v(LOG_TAG, "Clearing recordingBufferCache..");
                    recordingBufferCache = new short[recordingLength];
                    recordingOffset = 0; 
                    //!TODO assert that recordingBuffer is populated
                    
                //when buffer exeeds max record length, trim and resize the buffer, append, and then start inference
                //Resets recording buffer after inference
                }else if(countNumOfInferences < numOfInferences && recordingOffsetCount > recordingLength){
                
                    Log.v(LOG_TAG, "Recording buffer exceeded maximum threshold");
                    Log.v(LOG_TAG, "Trimming recording frame to remaining recording buffer..");
                    // int remainingRecordingLength = recordingLength - recordingOffset - 1; 
                    int remainingRecordingFrame = recordingOffset + numberRead - recordingLength; //16200 -> 200 remaining 
                    int remainingRecordingLength = recordingLength - recordingOffset; //15800
                    short [] resizedRecordingFrame = Arrays.copyOf(recordingFrame, remainingRecordingLength);
                    System.arraycopy(resizedRecordingFrame, 0, recordingBufferCache, recordingOffset, remainingRecordingLength);
                    recordingOffset += remainingRecordingLength;
                    //!Todo assert that recordingOffset = 16000

                    Log.v(LOG_TAG, "Recording trimmed and appended at length: " + remainingRecordingLength);
                    Log.v(LOG_TAG, "recordingOffset: " + (recordingOffset) + "/" + recordingLength);    //should output max recording length

                    recordingBuffer = recordingBufferCache;
                    startRecognition();
                    
                    Log.v(LOG_TAG, "Clearing recording buffer..");
                    Log.v(LOG_TAG, "Appending remaining recording frame to new recording buffer..");
                    recordingBufferCache = new short[recordingLength];
                    recordingOffset = 0 + remainingRecordingFrame; //200/16000
                    System.arraycopy(recordingFrame, 0, recordingBufferCache, recordingOffset, numberRead);
                    Log.v(LOG_TAG, "recordingOffset: " + recordingOffset + "/" + recordingLength);  
                  

                //when count reaches max numOfInferences, stop all inference and recording
                //no need to count recordingOffset with numberRead as its final
                }else if(countNumOfInferences == numOfInferences && recordingOffsetCount > recordingLength){
                    
                    Log.v(LOG_TAG, "Reached indicated number of inferences.");
                    Log.v(LOG_TAG, "Recording buffer exceeded maximum threshold");
                    Log.v(LOG_TAG, "Trimming recording frame to remaining recording buffer..");
                
                    int remainingRecordingFrame = recordingOffset + numberRead - recordingLength; //16200 -> 200 remaining 
                    int remainingRecordingLength = recordingLength - recordingOffset; //15800
                    short [] resizedRecordingFrame = Arrays.copyOf(recordingFrame, remainingRecordingLength);
                    System.arraycopy(resizedRecordingFrame, 0, recordingBufferCache, recordingOffset, remainingRecordingLength);
                    Log.v(LOG_TAG, "recordingOffset: " + (recordingOffset + remainingRecordingLength) + "/" + recordingLength);    //should output max recording length
                    Log.v(LOG_TAG, "Unused excess recording length: " + remainingRecordingLength);

                    recordingBuffer = recordingBufferCache;
                    lastInferenceRun = true;

                    startRecognition();
                    stopRecording();

                    //reset after recognition and recording. Don't change position!!
                    recordingOffset = 0;
                    countNumOfInferences = 1;

                         
                //stop recording once numOfInference is reached.
                }else if(countNumOfInferences == numOfInferences && recordingOffsetCount == recordingLength){
                    Log.v(LOG_TAG, "Reached indicated number of inferences.");
                    
                    System.arraycopy(recordingFrame, 0, recordingBufferCache, recordingOffset, numberRead);
                    recordingBuffer = recordingBufferCache;
                    lastInferenceRun = true;

                    startRecognition();
                    stopRecording();

                     //reset after recognition and recording. Don't change position!!
                     recordingOffset = 0;
                     countNumOfInferences = 1;

                //For debugging - Stop recognition/recording for unusual situations
                }else{
                    
                    lastInferenceRun = true;
                    forceStopRecogniton();

                    Log.v(LOG_TAG, "something weird has happened"); 
                    Log.v(LOG_TAG, "-------------------------------------"); 
                    Log.v(LOG_TAG, "countNumOfInference: " + countNumOfInferences); 
                    Log.v(LOG_TAG, "numOfInference: " + numOfInferences); 
                    Log.v(LOG_TAG, "recordingOffset: " + recordingOffset);
                    Log.v(LOG_TAG, "recordingOffsetCount " + recordingOffsetCount);
                    Log.v(LOG_TAG, "recordingLength " + recordingLength);
                    Log.v(LOG_TAG, "-------------------------------------"); 

                    //reset after recognition and recording. Don't change position!!
                    recordingOffset = 0;
                    countNumOfInferences = 1;
                    
                }

            } finally {
                recordingBufferLock.unlock();

            }
        }
    }

    public synchronized void startRecognition() {
        if (recognitionThread != null) {
            return;
        }

        recognitionThread =
                new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                recognize();                       
                            }
                        });
        recognitionThread.start();
    }


    private void recognize() {
        Log.v(LOG_TAG, "Recognition started.");
        countNumOfInferences += 1;

         //catches null exception.
         if(events == null){
            throw new AssertionError("Events is null. Cannot start recognition");
        }

        int[] inputShape = tfLite.getInputTensor(0).shape();
        String inputShapeMsg = Arrays.toString(inputShape);
        Log.v(LOG_TAG, "Input shape: " + inputShapeMsg);

       //determine rawAudio or decodedWav input
        float[][] floatInputBuffer = {};
        int[] sampleRateList = {};
        float[][] floatOutputBuffer = new float[1][labels.size()];
        short[] inputBuffer = new short[recordingLength]; 

        //Used for multiple input and outputs (decodedWav)
        Object[] inputArray = {};
        Map<Integer, Object> outputMap = new HashMap<>();
        Map<String, Object> finalResults = new HashMap();

        switch (inputType) {
            case "decodedWav": 
                Log.v(LOG_TAG, "InputType: " + inputType);
                floatInputBuffer = new float[recordingLength][1];
                sampleRateList = new int[]{sampleRate};
                
                inputArray = new Object[]{floatInputBuffer, sampleRateList};        
                outputMap.put(0, floatOutputBuffer);
            break;

            case "rawAudio":
                Log.v(LOG_TAG, "InputType: " + inputType);
                if(inputShape[0] > inputShape[1] && inputShape[1] == 1){
                    //[recordingLength, 1]
                    floatInputBuffer = new float[recordingLength][1];
                   
                }else if(inputShape[0] < inputShape[1] && inputShape[0] == 1){
                    //[1, recordingLength]
                    floatInputBuffer = new float[1][recordingLength];
                }
                // else{
                //     throw new Exception("input shape: " + inputShapeMsg + " does not match with rawAudio");
                // } 
            break;
    }


        recordingBufferLock.lock();
        try {
            int maxLength = recordingBuffer.length;
            System.arraycopy(recordingBuffer, 0, inputBuffer, 0, maxLength);
        } finally {
            recordingBufferLock.unlock();
        }
  

        long startTime = new Date().getTime();
        switch (inputType) {
            case "decodedWav": 
                // We need to feed in float values between -1.0 and 1.0, so divide the
                // signed 16-bit inputs.
                for (int i = 0; i < recordingLength; ++i) {
                    floatInputBuffer[i][0] = inputBuffer[i] / 32767.0f;
                }

                tfLite.runForMultipleInputsOutputs(inputArray, outputMap);
                lastProcessingTimeMs = new Date().getTime() - startTime;
            break;

            case "rawAudio":
                // We need to feed in float values between -1.0 and 1.0, so divide the
                 // signed 16-bit inputs.
                for (int i = 0; i < recordingLength; ++i) {
                    floatInputBuffer[0][i] = inputBuffer[i] / 32767.0f;
                }

                tfLite.run(floatInputBuffer, floatOutputBuffer);
                lastProcessingTimeMs = new Date().getTime() - startTime;
            break;
    }

        // debugging purposes
        Log.v(LOG_TAG, "Raw Scores: " + Arrays.toString(floatOutputBuffer[0]));
        // Log.v(LOG_TAG, Long.toString(lastProcessingTimeMs));

        if(outputRawScores == false){
            labelSmoothing =
            new LabelSmoothing(
                    labels,
                    averageWindowDuration,
                    detectionThreshold,
                    suppressionTime,
                    minimumTimeBetweenSamples);

            long currentTime = System.currentTimeMillis();
            final LabelSmoothing.RecognitionResult recognitionResult =
                    labelSmoothing.processLatestResults(floatOutputBuffer[0], currentTime);
            finalResults.put("recognitionResult", recognitionResult.foundCommand);
        }else{
            finalResults.put("recognitionResult", Arrays.toString(floatOutputBuffer[0]));
        }

        finalResults.put("inferenceTime", lastProcessingTimeMs);
        finalResults.put("hasPermission", true);

        getResult(finalResults);
        stopRecognition();
    }

    //passes map to from platform to flutter.
    public void getResult(Map<String, Object> recognitionResult) {

        //passing data from platform to flutter requires ui thread
        runOnUIThread(() -> {
            if (events != null) {
                Log.v(LOG_TAG, "result: " + recognitionResult.toString());
                events.success(recognitionResult);
            }
        });
    }

    public void stopRecognition() {
        // if recognitThread hasn't been called. The function will break
        if (recognitionThread == null) {
            return;
        }

        Log.d(LOG_TAG, "Recognition stopped.");
        recognitionThread = null;

        if (lastInferenceRun == true) {
            //passing data from platform to flutter requires ui thread
            runOnUIThread(() -> {
                if (events != null) {
                    Log.d(LOG_TAG, "Recognition Stream stopped");
                    events.endOfStream();
                }
            });
            lastInferenceRun = false;
        }
    }

    public void stopRecording() {
        
        if (recordingThread == null || shouldContinue == false ) {
            Log.d(LOG_TAG, "Recording has already stopped. Breaking stopRecording()");
            return;
        }

        shouldContinue = false;

        record.stop();
        record.release();
       
        recordingThread = null;
        Log.d(LOG_TAG, "Recording stopped.");
    }

    public void forceStopRecogniton() {

        stopRecording();
        stopRecognition();

        //passing data from platform to flutter requires ui thread
        runOnUIThread(() -> {
            if (events != null) {
                Log.d(LOG_TAG, "Recognition Stream stopped");
                events.endOfStream();
            }
        });
    }


    private void runOnUIThread(Runnable runnable) {
        if (Looper.getMainLooper() == Looper.myLooper())
            runnable.run();
        else
            handler.post(runnable);
    }

}


