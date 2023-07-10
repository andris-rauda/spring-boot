package org.springframework.boot.actuate.profiler;

import one.profiler.AsyncProfiler;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.actuate.endpoint.web.annotation.RestControllerEndpoint;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.WebRequest;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <p>
 * Spring Actuator for invoking
 * <a href="https://github.com/async-profiler/async-profiler">Async Profiler</a> via web
 * endpoint.
 * </p>
 * <p>
 * Requires async-profiler dependency to be added to project, for Maven this would look
 * somethig like this: <pre>{@code
 *     <dependency>
 *         <groupId>tools.profiler</groupId>
 *         <artifactId>async-profiler</artifactId>
 *         <version>2.9</version>
 *     </dependency>
 * }</pre>
 * </p>
 * <p>
 * Enable by activating "<i>profiler</i>" endpoint, for example, with following config in
 * {@code application.yaml}: <pre>{@code
 *   endpoints:
 *     web:
 *       exposure:
 *         include: profiler
 * }</pre>
 * </p>
 * <p>
 * Usage: profiler commands can be triggered by issuing GET requests on
 * <i>/actuator/profiler/{operation}</i> endpoint. <br/>
 * Path variable and additional request parameters are translated into <a href=
 * "https://github.com/async-profiler/async-profiler/blob/v2.9/src/arguments.cpp#L52">
 * AsyncProfile execution arguments</a>. Currently <i>dump</i> and <i>stop</i> operations
 * will produce flame-graph html, all other operations will simply return output generated
 * by AsyncProfiler.<br/>
 * Examples:
 * <ul>
 * <li>https://.../actuator/profiler/start - start profiling (will add event=cpu by
 * default)</li>
 * <li>https://.../actuator/profiler/start?event=wall - start wall profiling</li>
 * <li>https://.../actuator/profiler/stop - stop profiling and download flame-graph</li>
 * </ul>
 * </p>
 * <p>
 * Additionally, request on common <i>/actuator/profiler</i> endpoint will trigger
 * composite start, wait, stop operation for a specified duration (5 seconds by default).
 * </p>
 * Example:
 * <ul>
 * <li>https://.../actuator/profiler?event=wall&amp;duration=10 - invoke wall profiling
 * for 10 seconds and download flame-graph immediately.</li>
 * </ul>
 *
 * @author Andris Rauda
 */
// @ConditionalOnClass(AsyncProfiler.class)
@RestControllerEndpoint(id = "profiler")
public class AsyncProfilerEndpoint {

	private static final Log log = LogFactory.getLog(AsyncProfilerEndpoint.class);

	private static final String OPERATION_START = "start";

	public AsyncProfilerEndpoint() {
		try {
			log.info("AsyncProfilerEndpoint activated with " + AsyncProfiler.getInstance().getVersion());
		}
		catch (RuntimeException e) {
			log.warn("AsyncProfilerEndpoint not available: " + e.getMessage());
		}
	}

	@GetMapping("{operation:^(?!dump|stop).+}")
	public ResponseEntity<String> executeCommand(@PathVariable String operation, WebRequest request) {
		if (log.isDebugEnabled()) {
			log.debug("operation: " + operation);
			log.debug("parameters: " + ToStringBuilder.reflectionToString(request.getParameterMap()));
		}

		final String command = getCommand(operation, request);
		log.info("command: " + command);

		final String result;
		try {
			result = AsyncProfiler.getInstance().execute(command);
			log.info(result);
			return ResponseEntity.ok(result);
		}
		catch (IOException | RuntimeException e) {
			log.error("Failed to invoke AsyncProfiler " + operation, e);
			return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
		}
	}

	@GetMapping("{operation:(?:dump|stop)}")
	public ResponseEntity<Resource> collectFlameGraph(@PathVariable String operation) {
		if (log.isDebugEnabled()) {
			log.debug("operation: " + operation);
		}
		File file = null;
		try {
			file = createTempFile();
			final String command = operation + ",file=" + file.getAbsolutePath();
			log.info("command: " + command);
			log.info(AsyncProfiler.getInstance().execute(command));
			return new ResponseEntity<>(new AsyncProfilerEndpoint.TemporaryFileSystemResource(file), HttpStatus.OK);
		}
		catch (IOException | RuntimeException e) {
			log.error("Failed to invoke AsyncProfiler " + operation, e);
			if (file != null) {
				file.delete();
			}
			return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	@GetMapping
	public ResponseEntity<Resource> executeAndCollectFlamegraph(
			@RequestParam(value = "duration", required = false, defaultValue = "5") long duration, WebRequest request) {

		try {
			final long durationMillis = duration * 1000L;
			if (log.isDebugEnabled()) {
				log.debug("parameters: " + ToStringBuilder.reflectionToString(request.getParameterMap()));
			}

			final String command = getCommand("start", request);
			// if (this.lock.tryLock(this.lockTimeout, TimeUnit.MILLISECONDS) &&
			// "inactive".equals(AsyncProfiler.getInstance().execute("status"))) {
			log.info("duration: " + durationMillis + ", command: " + command);
			log.info(AsyncProfiler.getInstance().execute(command));
			Thread.sleep(durationMillis);
			return collectFlameGraph("stop");
			// } else {
			// return new ResponseEntity<>(HttpStatus.TOO_MANY_REQUESTS);
			// }
		}
		catch (IOException | RuntimeException e) {
			log.error("Failed to invoke AsyncProfiler", e);
			return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new ResponseEntity<>(HttpStatus.SERVICE_UNAVAILABLE);
		}
	}

	private static String getCommand(String operation, WebRequest request) {
		String parameters = request.getParameterMap().entrySet().stream()
				.filter(e -> !"duration".equalsIgnoreCase(e.getKey()))
				.map(e -> parseParameter(e.getKey(), e.getValue())).collect(Collectors.joining(","));

		if (OPERATION_START.equals(operation) && parameters.isBlank()) {
			parameters = "event=cpu";
		}
		return operation + "," + parameters;
	}

	private static String parseParameter(String key, String[] values) {
		if (values == null || values.length == 0
				|| (values.length == 1 && (values[0] == null || values[0].isBlank()))) {
			return key;
		}
		return Stream.of(values).map(v -> key + "=" + v).collect(Collectors.joining(","));
	}

	private File createTempFile() throws IOException {
		String date = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm").format(LocalDateTime.now());
		File file = File.createTempFile("async-profiler-" + date, ".html");
		file.delete();
		return file;
	}

	// TODO extract common class from HeapDumpWebEndpoint
	private static final class TemporaryFileSystemResource extends FileSystemResource {

		private final Log logger = LogFactory.getLog(getClass());

		private TemporaryFileSystemResource(File file) {
			super(file);
		}

		@Override
		public ReadableByteChannel readableChannel() throws IOException {
			ReadableByteChannel readableChannel = super.readableChannel();
			return new ReadableByteChannel() {

				@Override
				public boolean isOpen() {
					return readableChannel.isOpen();
				}

				@Override
				public void close() throws IOException {
					closeThenDeleteFile(readableChannel);
				}

				@Override
				public int read(ByteBuffer dst) throws IOException {
					return readableChannel.read(dst);
				}

			};
		}

		@Override
		public InputStream getInputStream() throws IOException {
			return new FilterInputStream(super.getInputStream()) {

				@Override
				public void close() throws IOException {
					closeThenDeleteFile(this.in);
				}

			};
		}

		private void closeThenDeleteFile(Closeable closeable) throws IOException {
			try {
				closeable.close();
			}
			finally {
				deleteFile();
			}
		}

		private void deleteFile() {
			try {
				Files.delete(getFile().toPath());
			}
			catch (IOException ex) {
				AsyncProfilerEndpoint.TemporaryFileSystemResource.this.logger
						.warn("Failed to delete temporary heap dump file '" + getFile() + "'", ex);
			}
		}

		@Override
		public boolean isFile() {
			// Prevent zero-copy so we can delete the file on close
			return false;
		}

	}

}