package org.apache.plugins;

import static java.util.stream.Collectors.toList;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.InstanceProfileCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.logs.AWSLogsClient;
import com.amazonaws.services.logs.model.*;

public class CloudwatchClient {

	protected static CloudwatchClient instance;

	public static synchronized CloudwatchClient getInstance() {
		try {
			if (instance == null) {
				instance = new CloudwatchClient();
				instance.init();
			}
			return instance;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * The queue / buffer size
	 */
	private final static int queueLength = 1024;

	/**
	 * The queue used to buffer log entries
	 */
	private LinkedBlockingQueue<LogRecord> loggingEventsQueue
			= new LinkedBlockingQueue<>(queueLength);

	/**
	 * the AWS Cloudwatch Logs API client
	 */
	protected AWSLogsClient awsLogsClient;

	private Formatter formatter = new SimpleFormatter();

	private AtomicReference<String> lastSequenceToken = new AtomicReference<>();

	/**
	 * The AWS Cloudwatch Log group name
	 */
	private String logGroupName;

	/**
	 * The AWS Cloudwatch Log stream name
	 */
	private String logStreamName;

	/**
	 * The maximum number of log entries to send in one go to the AWS Cloudwatch
	 * Log service
	 */
	private final static int messagesBatchSize = 128;

	private ScheduledThreadPoolExecutor exe;

	public CloudwatchClient() {
		super();
		try {
			logGroupName
					= "/tomcat/" + InetAddress.getLocalHost().getHostName();
			logStreamName = Instant.now().toString().replace(':', '.');
			init();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public CloudwatchClient(Formatter formatter, String logGroupName,
			String logStreamName) {
		super();
		this.setFormatter(formatter);
		this.setLogGroupName(logGroupName);
		this.setLogStreamName(logStreamName);
		this.flush();
	}

	public void setLogGroupName(String logGroupName) {
		this.logGroupName = logGroupName;
	}

	public void setLogStreamName(String logStreamName) {
		this.logStreamName = logStreamName;
	}

	public Formatter getFormatter() {
		return formatter;
	}

	public void setFormatter(Formatter formatter) {
		this.formatter = formatter;
	}

	private synchronized void sendMessages() {
		LogRecord polledLoggingEvent;
		List<LogRecord> loggingEvents = new ArrayList<>();
		try {
			while ((polledLoggingEvent = loggingEventsQueue.poll()) != null
					&& loggingEvents.size() <= messagesBatchSize) {
				loggingEvents.add(polledLoggingEvent);
			}

			List<InputLogEvent> inputLogEvents = loggingEvents.stream()
					.sorted(Comparator.comparing(LogRecord::getMillis))
					.map(loggingEvent -> {
						String msg;
						if (loggingEvent instanceof JsonLogRecord) {
							msg = ((JsonLogRecord) loggingEvent).getMessage();
						} else {
							msg = formatter.format(loggingEvent);
						}
						return new InputLogEvent()
								.withTimestamp(loggingEvent.getMillis())
								.withMessage(msg);
					}).collect(toList());

			if (!loggingEvents.isEmpty()) {
				PutLogEventsRequest putLogEventsRequest
						= new PutLogEventsRequest(logGroupName, logStreamName,
								inputLogEvents);
				try {
					putLogEventsRequest
							.setSequenceToken(lastSequenceToken.get());
					PutLogEventsResult result
							= awsLogsClient.putLogEvents(putLogEventsRequest);
					lastSequenceToken.set(result.getNextSequenceToken());
				} catch (InvalidSequenceTokenException invalidSequenceTokenException) {
					System.err.println("Resetting sequenceToken");
					putLogEventsRequest
							.setSequenceToken(invalidSequenceTokenException
									.getExpectedSequenceToken());
					PutLogEventsResult result
							= awsLogsClient.putLogEvents(putLogEventsRequest);
					lastSequenceToken.set(result.getNextSequenceToken());
				}
			}
		} catch (Exception e) {
			// should never happen
			System.err.println("IGNORED: " + e.toString());
			e.printStackTrace();
		}
	}

	protected void initCloudwatchDaemon() {
		exe = new ScheduledThreadPoolExecutor(1);
		exe.scheduleAtFixedRate(() -> {
			if (loggingEventsQueue.size() > 0) {
				sendMessages();
			}
		}, 0, 1, TimeUnit.SECONDS);
	}

	private void initializeCloudwatchResources() {
		DescribeLogGroupsRequest describeLogGroupsRequest
				= new DescribeLogGroupsRequest();
		describeLogGroupsRequest.setLogGroupNamePrefix(logGroupName);
		Optional<LogGroup> logGroupOptional
				= awsLogsClient.describeLogGroups(describeLogGroupsRequest)
						.getLogGroups().stream().filter(logGroup -> logGroup
								.getLogGroupName().equals(logGroupName))
						.findFirst();
		if (!logGroupOptional.isPresent()) {
			CreateLogGroupRequest createLogGroupRequest
					= new CreateLogGroupRequest()
							.withLogGroupName(logGroupName);
			awsLogsClient.createLogGroup(createLogGroupRequest);
		}
		DescribeLogStreamsRequest describeLogStreamsRequest
				= new DescribeLogStreamsRequest().withLogGroupName(logGroupName)
						.withLogStreamNamePrefix(logStreamName);
		Optional<LogStream> logStreamOptional
				= awsLogsClient.describeLogStreams(describeLogStreamsRequest)
						.getLogStreams().stream().filter(logStream -> logStream
								.getLogStreamName().equals(logStreamName))
						.findFirst();
		if (!logStreamOptional.isPresent()) {
			System.out.println("About to create LogStream: " + logStreamName
					+ " in LogGroup: " + logGroupName);
			CreateLogStreamRequest createLogStreamRequest
					= new CreateLogStreamRequest()
							.withLogGroupName(logGroupName)
							.withLogStreamName(logStreamName);
			awsLogsClient.createLogStream(createLogStreamRequest);
		}
	}

	private boolean isBlank(String string) {
		return null == string || string.trim().length() == 0;
	}

	public void publish(LogRecord record) {
		loggingEventsQueue.add(record);
	}

	public synchronized void init() throws IOException {
		try {
			if (isBlank(logGroupName) || isBlank(logStreamName)) {
				System.out.println(
						"Could not initialise CloudwatchAppender because either or both LogGroupName("
								+ logGroupName + ") and LogStreamName("
								+ logStreamName + ") are null or empty");
				this.close();
			} else {
				initializeClient();
				initializeCloudwatchResources();
				initCloudwatchDaemon();
				System.err.println(
						"Initialized CloudwatchAppender with LogGroupName("
								+ logGroupName + ") and LogStreamName("
								+ logStreamName + ")");
			}
		} catch (Exception e) {
			System.err.println(
					"Could not initialise Cloudwatch Logs for LogGroupName: "
							+ logGroupName + " and LogStreamName: "
							+ logStreamName + ": " + e.toString());
			throw e;
		}

	}

	public void initializeClient() throws IOException {
		File cliCreds = new File(System.getenv("HOME") + "/.aws/config");
		if (cliCreds.exists()) {
			System.out.println(
					"Reading credentials from " + cliCreds.getAbsolutePath());
			Properties p = new Properties();
			try (FileInputStream fis = new FileInputStream(cliCreds)) {
				p.load(fis);
			}
			this.awsLogsClient
					= new AWSLogsClient(new AWSCredentialsProvider() {

						@Override
						public void refresh() {
						}

						@Override
						public AWSCredentials getCredentials() {
							return new AWSCredentials() {

								@Override
								public String getAWSSecretKey() {
									return p.getProperty(
											"aws_secret_access_key");
								}

								@Override
								public String getAWSAccessKeyId() {
									return p.getProperty("aws_access_key_id");
								}
							};
						}
					});

			this.awsLogsClient.setRegion(Region
					.getRegion(Regions.fromName(p.getProperty("region"))));
		} else {
			System.out.println("Reading AWS credentials from instance profile");
			this.awsLogsClient = new AWSLogsClient(
					new InstanceProfileCredentialsProvider());

			Region currentRegion = Regions.getCurrentRegion();
			if (currentRegion == null) {
				currentRegion = Region.getRegion(Regions.US_WEST_1);
			}
			this.awsLogsClient.setRegion(currentRegion);
		}
	}

	public synchronized void close() throws SecurityException {
		exe.shutdown();
		try {
			exe.awaitTermination(2, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
		}
		flush();
	}

	public void flush() {
		sendMessages();
	}
}