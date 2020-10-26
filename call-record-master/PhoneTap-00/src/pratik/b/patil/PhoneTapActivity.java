package pratik.b.patil;

import android.app.Activity;
import android.os.Bundle;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.os.Environment;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.DialogInterface;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.PowerManager.WakeLock;
import android.provider.MediaStore;

import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

public class PhoneTapActivity extends Activity implements Recorder.OnStateChangedListener {
	static final String TAG = "PhoneTap";
	
    /** Called when the activity is first created. */ 
	static final String STATE_FILE_NAME = "soundrecorder.state";
    static final String RECORDER_STATE_KEY = "recorder_state";
    static final String SAMPLE_INTERRUPTED_KEY = "sample_interrupted";
    static final String MAX_FILE_SIZE_KEY = "max_file_size";

    static final String AUDIO_3GPP = "audio/3gpp";
    static final String AUDIO_AMR = "audio/amr";
    static final String AUDIO_EVRC = "audio/evrc";
    static final String AUDIO_QCELP = "audio/qcelp";
    static final String AUDIO_AAC_MP4 = "audio/aac_mp4";
    static final String AUDIO_ANY = "audio/*";
    static final String ANY_ANY = "*/*";
    
    static final int BITRATE_AMR =  5900; // bits/sec
    static final int BITRATE_3GPP = 5900;
    int mAudioSourceType = MediaRecorder.AudioSource.MIC;
    static int mOldCallState = TelephonyManager.CALL_STATE_IDLE;
    WakeLock mWakeLock;
    String mRequestedType = AUDIO_ANY;
    Recorder mRecorder;
    boolean mSampleInterrupted = false;    
    String mErrorUiMessage = null; // Some error messages are displayed in the UI, 
                                   // not a dialog. This happens when a recording
                                   // is interrupted for some reason.
    
    long mMaxFileSize = -1;        // can be specified in the intent
    RemainingTimeCalculator mRemainingTimeCalculator;
    
    String mTimerFormat;
    final Handler mHandler = new Handler();
    Runnable mUpdateTimer = new Runnable() {
        public void run() { //updateTimerView(); 
        }
    };
        TextView textOut;
        TextView textOut2;
        TelephonyManager telephonyManager;
        PhoneStateListener listener;
        private BroadcastReceiver mSDCardMountEventReceiver = null;

        /** Called when the activity is first created. */
        @Override
        public void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          setContentView(R.layout.main);
          // Get the UI
          textOut = (TextView) findViewById(R.id.textOut);
          textOut2 = (TextView) findViewById(R.id.textOut2);
          
          Intent i = getIntent();
          if (i != null) {
              String s = i.getType();
              if (AUDIO_AMR.equals(s) || AUDIO_3GPP.equals(s) || AUDIO_ANY.equals(s)
                      || ANY_ANY.equals(s)) {
                  mRequestedType = s;
              } else if (s != null) {
                  // we only support amr and 3gpp formats right now 
                  setResult(RESULT_CANCELED);
                  finish();
                  return;
              }
              
              final String EXTRA_MAX_BYTES
                  = android.provider.MediaStore.Audio.Media.EXTRA_MAX_BYTES;
              mMaxFileSize = i.getLongExtra(EXTRA_MAX_BYTES, -1);
          }
          
          if (AUDIO_ANY.equals(mRequestedType) || ANY_ANY.equals(mRequestedType)) {
              mRequestedType = AUDIO_3GPP;
          }
          
          mRequestedType = AUDIO_AMR; // Default type

//          setContentView(R.layout.main);

          mRecorder = new Recorder();
          mRecorder.setOnStateChangedListener(this);
          mRemainingTimeCalculator = new RemainingTimeCalculator();

          PowerManager pm 
              = (PowerManager) getSystemService(Context.POWER_SERVICE);
          mWakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, 
                                      "SoundRecorder");

        //  initResourceRefs();
          
          setResult(RESULT_CANCELED);
          registerExternalStorageListener();
          if (savedInstanceState != null) {
              Bundle recorderState = savedInstanceState.getBundle(RECORDER_STATE_KEY);
              if (recorderState != null) {
                  mRecorder.restoreState(recorderState);
                  mSampleInterrupted = recorderState.getBoolean(SAMPLE_INTERRUPTED_KEY, false);
                  mMaxFileSize = recorderState.getLong(MAX_FILE_SIZE_KEY, -1);
              }
          }
          
          //updateUi();          
       
          // Get the telephony manager
          telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);

          // Create a new PhoneStateListener
          listener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String incomingNumber) {
              String stateString = "N/A";
              
              switch (state) {
              case TelephonyManager.CALL_STATE_IDLE:
                stateString = "Idle";
                if ((mOldCallState == TelephonyManager.CALL_STATE_OFFHOOK) && !(mAudioSourceType == MediaRecorder.AudioSource.MIC)){
                    mRecorder.stop();
                    mAudioSourceType = MediaRecorder.AudioSource.MIC;
                 }
                break;
              case TelephonyManager.CALL_STATE_OFFHOOK:
                stateString = "Off Hook";
            	mOldCallState = TelephonyManager.CALL_STATE_OFFHOOK;
            	startRecording_Atul ();
                break;
              case TelephonyManager.CALL_STATE_RINGING:
                stateString = "Ringing";
                break;
              }
              // Temp UI Needs to Remove
              textOut.append(String.format("\n onCallStateChanged: %s", stateString));
             // String numString = telephonyManager.getLine1Number();
              String numString;
              numString = telephonyManager.getNetworkOperatorName();
              textOut2.append(String.format("\n incomingNumber: %s", incomingNumber));
            }
          };
          // See onResume : Register the listener wit the telephony manager
          //telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
        }
        
        void startRecording_Atul () {
        	if (AUDIO_AMR.equals(mRequestedType)) {
                mRemainingTimeCalculator.setBitRate(BITRATE_AMR);
                mRecorder.startRecording(MediaRecorder.OutputFormat.RAW_AMR, ".amr", this, mAudioSourceType, MediaRecorder.AudioEncoder.AMR_NB);
            } else if (AUDIO_3GPP.equals(mRequestedType)) {
                mRemainingTimeCalculator.setBitRate(BITRATE_3GPP);
                mRecorder.startRecording(MediaRecorder.OutputFormat.THREE_GPP, ".3gpp", this, mAudioSourceType, MediaRecorder.AudioEncoder.AMR_NB);
            } else {
                throw new IllegalArgumentException("Invalid output file type requested");
            }
            
            if (mMaxFileSize != -1) {
                mRemainingTimeCalculator.setFileSizeLimit(
                        mRecorder.sampleFile(), mMaxFileSize);
            }

        }
        @Override
        protected void onResume() {
            super.onResume();
            // While we're in the foreground, listen for phone state changes.
         // Register the listener wit the telephony manager
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
        }
        
        /*
         * Called on destroy to unregister the SD card mount event receiver.
         */
        @Override
        public void onDestroy() {
            if (mSDCardMountEventReceiver != null) {
                unregisterReceiver(mSDCardMountEventReceiver);
                mSDCardMountEventReceiver = null;
            }
            super.onDestroy();
        }
        
        @Override
        public void onStop() {
            mRecorder.stop();
            super.onStop();
        }

        @Override
        protected void onPause() {
            // Stop listening for phone state changes.
            // Register the listener wit the telephony manager
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
            mSampleInterrupted = mRecorder.state() == Recorder.RECORDING_STATE;
            mRecorder.stop();
            
            super.onPause();
        }
        
        // Rest of Methods : SD Card and Recording Related :
        /*
         * Registers an intent to listen for ACTION_MEDIA_EJECT/ACTION_MEDIA_MOUNTED
         * notifications.
         */
        private void registerExternalStorageListener() {
            if (mSDCardMountEventReceiver == null) {
                mSDCardMountEventReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        if (action.equals(Intent.ACTION_MEDIA_EJECT)) {
                            mRecorder.delete();
                        } else if (action.equals(Intent.ACTION_MEDIA_MOUNTED)) {
                            mSampleInterrupted = false;
                   //         updateUi();
                        }
                    }
                };
                IntentFilter iFilter = new IntentFilter();
                iFilter.addAction(Intent.ACTION_MEDIA_EJECT);
                iFilter.addAction(Intent.ACTION_MEDIA_MOUNTED);
                iFilter.addDataScheme("file");
                registerReceiver(mSDCardMountEventReceiver, iFilter);
            }
        }
        
        @Override
        protected void onSaveInstanceState(Bundle outState) {
            super.onSaveInstanceState(outState);
            
            if (mRecorder.sampleLength() == 0)
                return;

            Bundle recorderState = new Bundle();
            
            mRecorder.saveState(recorderState);
            recorderState.putBoolean(SAMPLE_INTERRUPTED_KEY, mSampleInterrupted);
            recorderState.putLong(MAX_FILE_SIZE_KEY, mMaxFileSize);
            
            outState.putBundle(RECORDER_STATE_KEY, recorderState);
        }
        
        /*
         * Make sure we're not recording music playing in the background, ask
         * the MediaPlaybackService to pause playback.
         */
        private void stopAudioPlayback() {
            // Shamelessly copied from MediaPlaybackService.java, which
            // should be public, but isn't.
            Intent i = new Intent("com.android.music.musicservicecommand");
            i.putExtra("command", "pause");

            sendBroadcast(i);
        }
        
        /*
         * Handle the "back" hardware key. 
         */
        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                switch (mRecorder.state()) {
                    case Recorder.IDLE_STATE:
                        if (mRecorder.sampleLength() > 0)
                            saveSample();
                        finish();
                        break;
                    case Recorder.PLAYING_STATE:
                        mRecorder.stop();
                        saveSample();
                        break;
                    case Recorder.RECORDING_STATE:
                        mRecorder.clear();
                        break;
                }
                return true;
            } else {
                return super.onKeyDown(keyCode, event);
            }
        }
        
        /*
         * If we have just recorded a smaple, this adds it to the media data base
         * and sets the result to the sample's URI.
         */
        private void saveSample() {
            if (mRecorder.sampleLength() == 0)
                return;
            Uri uri = null;
            try {
                //uri = this.addToMediaDB(mRecorder.sampleFile());
            } catch(UnsupportedOperationException ex) {  // Database manipulation failure
                return;
            }
            if (uri == null) {
                return;
            }
            setResult(RESULT_OK, new Intent().setData(uri));
        }
        
     // Voicememo Adding UI choice for the user to get the format needed
        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
             Log.v(TAG, "dispatchKeyEvent with key event" + event);
        if(event.getKeyCode() == KeyEvent.KEYCODE_6){
           if((mAudioSourceType == MediaRecorder.AudioSource.VOICE_CALL) ||
              (mAudioSourceType == MediaRecorder.AudioSource.VOICE_DOWNLINK)||
              (mAudioSourceType == MediaRecorder.AudioSource.VOICE_UPLINK )) {
              Resources res = getResources();
              String message = null;
             // message = res.getString(R.string.error_mediadb_aacincall);
              new AlertDialog.Builder(this)
              .setTitle(R.string.app_name)
              .setMessage(message)
              //.setPositiveButton(R.string.button_ok, null)
              .setCancelable(false)
              .show();
              return super.dispatchKeyEvent(event);
           }
        }

        if(event.getKeyCode() == KeyEvent.KEYCODE_1 || event.getKeyCode() == KeyEvent.KEYCODE_2){
           AudioManager audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
           if((audioManager.getMode() != AudioManager.MODE_IN_CALL) ||
             (mRequestedType == AUDIO_AAC_MP4)) {
              Resources res = getResources();
              String message = null;
              if(audioManager.getMode() != AudioManager.MODE_IN_CALL) {
              //  message = res.getString(R.string.error_mediadb_incall);
              } else {
              //  message = res.getString(R.string.error_mediadb_aacincall);
              }
              new AlertDialog.Builder(this)
              .setTitle(R.string.app_name)
              .setMessage(message)
              //.setPositiveButton(R.string.button_ok, null)
              .setCancelable(false)
              .show();
              return super.dispatchKeyEvent(event);
           }
        }
            // Intercept some events before they get dispatched to our views.
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_0: // MIC source (Camcorder)
                {
                  Log.e(TAG, "Selected MIC Source: Key Event" + KeyEvent.KEYCODE_0);
                  mAudioSourceType = MediaRecorder.AudioSource.MIC;
                  return true;
                }

                case KeyEvent.KEYCODE_1: // Voice Rx Only (Only during Call(
                {
                  Log.e(TAG, "Selected Voice Rx only Source: Key Event" + KeyEvent.KEYCODE_1);
                  mAudioSourceType = MediaRecorder.AudioSource.VOICE_DOWNLINK;
                  return true;
                }

                case KeyEvent.KEYCODE_2: // Voice Rx+Tx (Only during Call)
                {
                  Log.e(TAG, "Selected Voice Tx+Rx Source: Key Event" + KeyEvent.KEYCODE_2);
                  mAudioSourceType = MediaRecorder.AudioSource.VOICE_CALL;
                  return true;
                }

                case KeyEvent.KEYCODE_3: // Selected AMR codec type
                {
                  Log.e(TAG, "Selected AUDIO_AMR Codec: Key Event" + KeyEvent.KEYCODE_3);
                  mRequestedType = AUDIO_AMR;
                  return true;
                }

                case KeyEvent.KEYCODE_4: // Selected EVRC codec type
                {
                  Log.e(TAG, "Selected Voice AUDIO_EVRC Codec: Key Event" + KeyEvent.KEYCODE_4);
                  mRequestedType = AUDIO_EVRC;
                  return true;
                }

                case KeyEvent.KEYCODE_5: // Selected QCELP codec type
                {
                  Log.e(TAG, "Selected AUDIO_QCELP Codec: Key Event" + KeyEvent.KEYCODE_5);
                  mRequestedType = AUDIO_QCELP;
                  return true;
                }
                case KeyEvent.KEYCODE_6: // Selected AAC codec type
                {
                  Log.e(TAG, "Selected AUDIO_AAC_MP4 Codec: Key Event" + KeyEvent.KEYCODE_6);
                  mRequestedType = AUDIO_AAC_MP4;
                  return true;
                }

                default:
                    break;
            }

            return super.dispatchKeyEvent(event);
        }
        /*
         * Called when Recorder changed it's state.
         */
        public void onStateChanged(int state) {
            if (state == Recorder.PLAYING_STATE || state == Recorder.RECORDING_STATE) {
                mSampleInterrupted = false;
                mErrorUiMessage = null;
            }
            
            if (state == Recorder.RECORDING_STATE) {
                mWakeLock.acquire(); // we don't want to go to sleep while recording
            } else {
                if (mWakeLock.isHeld())
                    mWakeLock.release();
            }
            
         //   updateUi();
        }
        
        /*
         * Called when MediaPlayer encounters an error.
         */
        public void onError(int error) {
            Resources res = getResources();
            boolean isExit = false;

            String message = null;
            switch (error) {
                case Recorder.SDCARD_ACCESS_ERROR:
              //      message = res.getString(R.string.error_sdcard_access);
                    break;
                case Recorder.IN_CALL_RECORD_ERROR:
                    // TODO: update error message to reflect that the recording could not be
                    //       performed during a call.
                case Recorder.INTERNAL_ERROR:
                  //  message = res.getString(R.string.error_app_internal);
                    isExit = true;
                    break;
                case Recorder.UNSUPPORTED_FORMAT:
                  //  message = res.getString(R.string.error_app_unsupported);
                    isExit = true;
                    break;
            }
            if (message != null) {/*
                new AlertDialog.Builder(this)
                    .setTitle(R.string.app_name)
                    .setMessage(message)
                    .setPositiveButton(R.string.button_ok, (true==isExit)?
                        (new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int whichButton) {
                                finish();
                            }}):null)
                    .setCancelable(false)
                    .show();
                    */
            }
        }
}

/**
 * Calculates remaining recording time based on available disk space and
 * optionally a maximum recording file size.
 * 
 * The reason why this is not trivial is that the file grows in blocks
 * every few seconds or so, while we want a smooth countdown.
 */

class RemainingTimeCalculator {
    public static final int UNKNOWN_LIMIT = 0;
    public static final int FILE_SIZE_LIMIT = 1;
    public static final int DISK_SPACE_LIMIT = 2;
    
    // which of the two limits we will hit (or have fit) first
    private int mCurrentLowerLimit = UNKNOWN_LIMIT;
    
    private File mSDCardDirectory;
    
     // State for tracking file size of recording.
    private File mRecordingFile;
    private long mMaxBytes;
    
    // Rate at which the file grows
    private int mBytesPerSecond;
    
    // time at which number of free blocks last changed
    private long mBlocksChangedTime;
    // number of available blocks at that time
    private long mLastBlocks;
    
    // time at which the size of the file has last changed
    private long mFileSizeChangedTime;
    // size of the file at that time
    private long mLastFileSize;
    
    public RemainingTimeCalculator() {
        mSDCardDirectory = Environment.getExternalStorageDirectory();
    }    
    
    /**
     * If called, the calculator will return the minimum of two estimates:
     * how long until we run out of disk space and how long until the file
     * reaches the specified size.
     * 
     * @param file the file to watch
     * @param maxBytes the limit
     */
    
    public void setFileSizeLimit(File file, long maxBytes) {
        mRecordingFile = file;
        mMaxBytes = maxBytes;
    }
    
    /**
     * Resets the interpolation.
     */
    public void reset() {
        mCurrentLowerLimit = UNKNOWN_LIMIT;
        mBlocksChangedTime = -1;
        mFileSizeChangedTime = -1;
    }
    
    /**
     * Returns how long (in seconds) we can continue recording. 
     */
    public long timeRemaining() {
        // Calculate how long we can record based on free disk space
        
        StatFs fs = new StatFs(mSDCardDirectory.getAbsolutePath());
        long blocks = fs.getAvailableBlocks();
        long blockSize = fs.getBlockSize();
        long now = System.currentTimeMillis();
        
        if (mBlocksChangedTime == -1 || blocks != mLastBlocks) {
            mBlocksChangedTime = now;
            mLastBlocks = blocks;
        }

        /* The calculation below always leaves one free block, since free space
           in the block we're currently writing to is not added. This
           last block might get nibbled when we close and flush the file, but 
           we won't run out of disk. */
        
        // at mBlocksChangedTime we had this much time
        long result = mLastBlocks*blockSize/mBytesPerSecond;
        // so now we have this much time
        result -= (now - mBlocksChangedTime)/1000;
        
        if (mRecordingFile == null) {
            mCurrentLowerLimit = DISK_SPACE_LIMIT;
            return result;
        }
        
        // If we have a recording file set, we calculate a second estimate
        // based on how long it will take us to reach mMaxBytes.
        
        mRecordingFile = new File(mRecordingFile.getAbsolutePath());
        long fileSize = mRecordingFile.length();
        if (mFileSizeChangedTime == -1 || fileSize != mLastFileSize) {
            mFileSizeChangedTime = now;
            mLastFileSize = fileSize;
        }

        long result2 = (mMaxBytes - fileSize)/mBytesPerSecond;
        result2 -= (now - mFileSizeChangedTime)/1000;
        result2 -= 1; // just for safety
        
        mCurrentLowerLimit = result < result2
            ? DISK_SPACE_LIMIT : FILE_SIZE_LIMIT;
        
        return Math.min(result, result2);
    }
    
    /**
     * Indicates which limit we will hit (or have hit) first, by returning one 
     * of FILE_SIZE_LIMIT or DISK_SPACE_LIMIT or UNKNOWN_LIMIT. We need this to 
     * display the correct message to the user when we hit one of the limits.
     */
    public int currentLowerLimit() {
        return mCurrentLowerLimit;
    }

    /**
     * Is there any point of trying to start recording?
     */
    public boolean diskSpaceAvailable() {
        StatFs fs = new StatFs(mSDCardDirectory.getAbsolutePath());
        // keep one free block
        return fs.getAvailableBlocks() > 1;
    }

    /**
     * Sets the bit rate used in the interpolation.
     *
     * @param bitRate the bit rate to set in bits/sec.
     */
    public void setBitRate(int bitRate) {
        mBytesPerSecond = bitRate/8;
    }
}