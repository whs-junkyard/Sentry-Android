package com.joshdholtz.sentry;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.lang.Thread.UncaughtExceptionHandler;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import org.apache.http.Header;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHeader;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import com.joshdholtz.sentry.Sentry.SentryEventBuilder.SentryEventLevel;
import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;

public class Sentry {
	
	private final static String VERSION = "1.0";

	private String baseUrl;
	private String dsn;
	private String packageName;
	private Map<String, String> tags;
	private SentryEventCaptureListener captureListener;
	
	private AsyncHttpClient client;

	private static final String TAG = "Sentry";
	private static final String DEFAULT_BASE_URL = "https://app.getsentry.com";
	
	private Sentry() {

	}

	private static Sentry getInstance() {
		return LazyHolder.instance;
	}


	private static class LazyHolder {
		private static Sentry instance = new Sentry();
	}

	public static void init(Context context, String dsn) {
		init(context, DEFAULT_BASE_URL, dsn, new HashMap<String, String>());
	}

	public static void init(Context context, String baseUrl, String dsn) {
		init(context, baseUrl, dsn, new HashMap<String, String>());
	}

	public static void init(Context context, String baseUrl, String dsn, Map<String, String> tags) {
		Sentry instance = Sentry.getInstance();
		instance.dsn = dsn;
		instance.packageName = context.getPackageName();
		instance.tags = tags;
		instance.baseUrl = baseUrl;

		instance.client = new AsyncHttpClient();
		instance.client.setUserAgent("sentry-android/" + VERSION);

		submitStackTraces(context);

		UncaughtExceptionHandler currentHandler = Thread.getDefaultUncaughtExceptionHandler();
		if (currentHandler != null) {
			Log.d("Debugged", "current handler class="+currentHandler.getClass().getName());
		}       
		// don't register again if already registered
		if (!(currentHandler instanceof SentryUncaughtExceptionHandler)) {
			// Register default exceptions handler
			Thread.setDefaultUncaughtExceptionHandler(
					new SentryUncaughtExceptionHandler(currentHandler, context));
		}
	}
	
	private static String createXSentryAuthHeader() {
		String header = "";
		
		Uri uri = Uri.parse(Sentry.getInstance().dsn);
		Log.d("Sentry", "URI - " + uri);
		String authority = uri.getAuthority().replace("@" + uri.getHost(), "");
		
		String[] authorityParts = authority.split(":");
		String publicKey = authorityParts[0];
		String secretKey = authorityParts[1];
		
		header += "Sentry sentry_version=4,";
		header += "sentry_client=sentry-android/" + VERSION + ",";
		header += "sentry_timestamp=" + System.currentTimeMillis() +",";
		header += "sentry_key=" + publicKey + ",";
		header += "sentry_secret=" + secretKey;
		
		return header;
	}
	
	private static String getProjectId() {
		Uri uri = Uri.parse(Sentry.getInstance().dsn);
		String path = uri.getPath();
		String projectId = path.substring(path.lastIndexOf("/") + 1);
		
		return projectId;
	}
	
	/**
	 * @param captureListener the captureListener to set
	 */
	public static void setCaptureListener(SentryEventCaptureListener captureListener) {
		Sentry.getInstance().captureListener = captureListener;
	}

	public static void captureMessage(String message) {
		Sentry.captureMessage(message, SentryEventLevel.INFO);
	}
	
	public static void captureMessage(String message, SentryEventLevel level) {
		Sentry.captureEvent(new SentryEventBuilder()
				.setMessage(message)
				.setLevel(level)
				.setTags(getInstance().tags)
		);
	}
	
	public static void captureException(Throwable t) {
		Sentry.captureException(t, SentryEventLevel.ERROR);
	}
	
	public static void captureException(Throwable t, SentryEventLevel level) {
		String culprit = getCause(t, t.getMessage());
		
		Sentry.captureEvent(new SentryEventBuilder()
			.setMessage(t.getMessage())
			.setCulprit(culprit)
			.setLevel(level)
			.setException(t)
			.setTags(getInstance().tags)
		);
		
		
	}

	public static void captureUncaughtException(Context context, Throwable t) {
		final Writer result = new StringWriter();
		final PrintWriter printWriter = new PrintWriter(result);
		t.printStackTrace(printWriter);
		try {
			// Random number to avoid duplicate files
			long random = System.currentTimeMillis();

			// Embed version in stacktrace filename
			File stacktrace = new File(getStacktraceLocation(context), "raven-" +  String.valueOf(random) + ".stacktrace");
			Log.d(TAG, "Writing unhandled exception to: " + stacktrace.getAbsolutePath());

			// Write the stacktrace to disk
			ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(stacktrace));
			oos.writeObject(t);
			oos.flush();
			// Close up everything
			oos.close();
		} catch (Exception ebos) {
			// Nothing much we can do about this - the game is over
			ebos.printStackTrace();
		}

		Log.d(TAG, result.toString());
	}

	private static String getCause(Throwable t, String culprit) {
		for (StackTraceElement stackTrace : t.getStackTrace()) {
			if (stackTrace.toString().contains(Sentry.getInstance().packageName)) {
				culprit = stackTrace.toString();
				break;
			}
		}
		
		return culprit;
	}

	private static File getStacktraceLocation(Context context) {
		return new File(context.getCacheDir(), "crashes");
	}
	
	private static String getStackTrace(Throwable t) {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		t.printStackTrace(pw);
		return sw.toString();
	}
	
	public static void captureEvent(SentryEventBuilder builder) {
		Sentry instance = Sentry.getInstance();
		if (instance.captureListener != null) {
			builder = instance.captureListener.beforeCapture(builder);
		}
		
		JSONObject requestData = new JSONObject(builder.event);
		
		Header[] headers = new Header[]{
				new BasicHeader("X-Sentry-Auth", createXSentryAuthHeader())
		};
		
		Log.d(TAG, "Request - " + requestData.toString());

		try {
			instance.client.post(
					null,
					instance.baseUrl + "/api/" + getProjectId() + "/store/",
					headers,
					new StringEntity(requestData.toString()), 
					"application/json; charset=utf-8",
					new AsyncHttpResponseHandler() {
					    @Override
					    public void onSuccess(int statusCode, Header[] headers, byte[] responseBody) {
					    	Log.d(TAG, "SendEvent - " + statusCode + " " + new String(responseBody));
					    }
					    @Override
					    public void onFailure(int statusCode, Header[] headers, byte[] responseBody, Throwable error){
					    	// http://sentry.whs.in.th/kusmartbus/android/group/138/
					    	String body;
					    	if(responseBody == null){
					    		body = "";
					    	}else{
					    		body = new String(responseBody);
					    	}
					    	Log.e(TAG, "SendEvent - " + statusCode + " " + body, error);
					    }
					}
			);
		} catch (UnsupportedEncodingException e) {
			Log.e(TAG, "SendEvent", e);
		}
	}

	private static class SentryUncaughtExceptionHandler implements UncaughtExceptionHandler {

		private UncaughtExceptionHandler defaultExceptionHandler;
		private Context context;

		// constructor
		public SentryUncaughtExceptionHandler(UncaughtExceptionHandler pDefaultExceptionHandler, Context context) {
			defaultExceptionHandler = pDefaultExceptionHandler;
			this.context = context;
		}

		@Override
		public void uncaughtException(Thread thread, Throwable e) {
			// Here you should have a more robust, permanent record of problems
			Sentry.captureUncaughtException(context, e);

			//call original handler  
			defaultExceptionHandler.uncaughtException(thread, e);  
		}

	}

	private static String[] searchForStackTraces(Context context) {
		File dir = getStacktraceLocation(context);
		// Try to create the files folder if it doesn't exist
		dir.mkdirs();
		// Filter for ".stacktrace" files
		FilenameFilter filter = new FilenameFilter() { 
			public boolean accept(File dir, String name) {
				return name.endsWith(".stacktrace"); 
			} 
		}; 
		return dir.list(filter); 
	}

	private static void submitStackTraces(final Context context) {
		try {
			Log.d(TAG, "Looking for exceptions to submit");
			String[] list = searchForStackTraces(context);
			if (list != null && list.length > 0) {
				Log.d(TAG, "Found "+list.length+" stacktrace(s)");
				for (int i=0; i < list.length; i++) {
					File stacktrace = new File(getStacktraceLocation(context), list[i]);

					ObjectInputStream ois = new ObjectInputStream(new FileInputStream(stacktrace));
					Throwable t = (Throwable) ois.readObject();
					ois.close();

					captureException(t, SentryEventLevel.FATAL);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			try {
				String[] list = searchForStackTraces(context);
				for ( int i = 0; i < list.length; i ++ ) {
					File file = new File(getStacktraceLocation(context), list[i]);
					file.delete();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

	}
	
	public abstract static class SentryEventCaptureListener {
		
		public abstract SentryEventBuilder beforeCapture(SentryEventBuilder builder);
		
	}
	
	public static class SentryEventBuilder {
		
		private final static SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
		static {
			sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
		}
		
		private Map<String, Object> event;
		
		public static enum SentryEventLevel {
			
			FATAL("fatal"),
			ERROR("error"),
			WARNING("warning"),
			INFO("info"),
			DEBUG("debug");
			
			private String value;
			SentryEventLevel(String value) {
				this.value = value;
			}
			
		}
		
		public SentryEventBuilder() {
			event = new HashMap<String, Object>();
			event.put("event_id", UUID.randomUUID().toString().replace("-", ""));
			this.setPlatform("android");
			this.setTimestamp(System.currentTimeMillis());
			this.setModule("android-sentry", Sentry.VERSION);
			//this.setModule(AsyncHttpClient.class.getPackage().getName(), new AsyncHttpClient());
		}
		
		/**
		 * "message": "SyntaxError: Wattttt!"
		 * @param message
		 * @return
		 */
		public SentryEventBuilder setMessage(String message) {
			event.put("message", message);
			return this;
		}
		
		/**
		 * "timestamp": "2011-05-02T17:41:36"
		 * @param timestamp
		 * @return
		 */
		public SentryEventBuilder setTimestamp(long timestamp) {
			event.put("timestamp", sdf.format(new Date(timestamp)));
			return this;
		}
		
		/**
		 * "level": "warning"
		 * @param level
		 * @return
		 */
		public SentryEventBuilder setLevel(SentryEventLevel level) {
			event.put("level", level.value);
			return this;
		}
		
		/**
		 * "logger": "my.logger.name"
		 * @param logger
		 * @return
		 */
		public SentryEventBuilder setLogger(String logger) {
			event.put("logger", logger);
			return this;
		}
		
		/**
		 * "platform": "python"
		 * @param platform
		 * @return
		 */
		public SentryEventBuilder setPlatform(String platform) {
			event.put("platform", platform);
			return this;
		}
		
		/**
		 * "culprit": "my.module.function_name"
		 * @param culprit
		 * @return
		 */
		public SentryEventBuilder setCulprit(String culprit) {
			event.put("culprit", culprit);
			return this;
		}
		
		/**
		 * 
		 * @param tags
		 * @return
		 */
		public SentryEventBuilder setTags(Map<String,String> tags) {
			setTags(new JSONObject(tags));
			return this;
		}
		
		public SentryEventBuilder setTags(JSONObject tags) {
			try{
				tags.put("device", Build.DEVICE);
	    	    tags.put("device_name", Build.MODEL);
	    	    tags.put("device_brand", Build.BRAND);
	    	    tags.put("android_version", String.valueOf(Build.VERSION.SDK_INT));
	    	    tags.put("android_version_name", Build.VERSION.RELEASE);
			}catch(JSONException e){
			}
			event.put("tags", tags);
			return this;
		}
		
		public JSONObject getTags() {
			if (!event.containsKey("tags")) {
				setTags(new HashMap<String, String>());
			}
			
			return (JSONObject) event.get("tags");
		}
		
		/**
		 * 
		 * @param serverName
		 * @return
		 */
		public SentryEventBuilder setServerName(String serverName) {
			event.put("server_name", serverName);
			return this;
		}
		
		/**
		 * 
		 * @param modules
		 * @return
		 */
		public SentryEventBuilder setModules(List<String> modules) {
			event.put("modules", new JSONArray(modules));
			return this;
		}
		
		/**
		 * 
		 * @param extra
		 * @return
		 */
		public SentryEventBuilder setExtra(Map<String,String> extra) {
			setExtra(new JSONObject(extra));
			return this;
		}
		
		public SentryEventBuilder setExtra(JSONObject extra) {
			event.put("extra", extra);
			return this;
		}
		
		public JSONObject getExtra() {
			if (!event.containsKey("extra")) {
				setExtra(new HashMap<String, String>());
			}
			
			return (JSONObject) event.get("extra");
		}
		
		public SentryEventBuilder setModule(String name, String version) {
			if(!event.containsKey("modules")){
				event.put("modules", new HashMap<String, String>());
			}
			((Map<String, String>) event.get("modules")).put(name, version);
			return this;
		} 
		
		public JSONObject getModule() {
			if(!event.containsKey("modules")){
				event.put("modules", new HashMap<String, String>());
			}
			return new JSONObject((Map<String, String>) event.get("modules"));
		}
		
		public SentryEventBuilder setChecksum(String checksum) {
			event.put("checksum", calculateChecksum(checksum));
			return this;
		}
		
		public String getChecksum(){
			return (String) event.get("checksum");
		}
		
		/**
		 *
		 * @param t
		 * @return
		 */
		public SentryEventBuilder setException(Throwable t) {
			ArrayList<JSONObject> array = new ArrayList<JSONObject>();
			
			while(t != null){
				Map<String, Object> exception = new HashMap<String, Object>();
				exception.put("type", t.getClass().getName());
				exception.put("value", t.getMessage());
		        try {
					exception.put("stacktrace", getStackTrace(t));
				} catch (JSONException e) { e.printStackTrace(); }
		        array.add(new JSONObject(exception));
		        t = t.getCause();
			}
			
			Collections.reverse(array);
			
			event.put("exception", new JSONArray(array));
			
			return this;
		}
		
		public static JSONObject getStackTrace(Throwable t) throws JSONException {
			ArrayList<JSONObject> array = new ArrayList<JSONObject>();
			Collection<String> notInApp = getNotInAppFrames();
			
            StackTraceElement[] elements = t.getStackTrace();
            for (int index = 0; index < elements.length; ++index) {
                StackTraceElement element = elements[index];
                JSONObject frame = new JSONObject();
                frame.put("filename", element.getFileName());
                frame.put("module", element.getClassName());
                frame.put("function", element.getMethodName());
                frame.put("lineno", element.getLineNumber());
                
                boolean inApp = true;
                for(String notInAppItem : notInApp){
                	if(element.getClassName().startsWith(notInAppItem)){
                		inApp = false;
                	}
                }
                frame.put("in_app", inApp);
                
                array.add(frame);
            }
	            
	        Collections.reverse(array);
	        JSONObject stackTrace = new JSONObject();
	        stackTrace.put("frames", new JSONArray(array));
	        return stackTrace;
	    }
		
		// source: net.kencochrane.raven.event.EventBuilder
		private static String calculateChecksum(String string) {
			try{
				byte[] bytes = string.getBytes("UTF-8");
				Checksum checksum = new CRC32();
		        checksum.update(bytes, 0, bytes.length);
		        return Long.toHexString(checksum.getValue()).toUpperCase();
			}catch(UnsupportedEncodingException e){
				return "";
			}
	    }
		
		// source: net.kencochrane.raven.DefaultRavenFactory
		protected static Collection<String> getNotInAppFrames() {
	        return Arrays.asList(
	        		"com.sun.",
	                "java.",
	                "javax.",
	                "org.omg.",
	                "sun.",
	                "junit.",
	                // android specific
	                "com.android.",
	                "android.",
	                "com.google.",
	                "libcore.",
	                "dalvik.",
	                "map.",
	                // app specific
	                Sentry.class.getPackage().getName()
	        );
	    }
		
	}

}
