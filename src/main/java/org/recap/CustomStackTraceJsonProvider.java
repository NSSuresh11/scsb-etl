package org.recap;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import net.logstash.logback.composite.JsonWritingUtils;
import net.logstash.logback.composite.loggingevent.StackTraceJsonProvider;
import tools.jackson.core.JsonGenerator;


/**
 * Created by rathin maheswaran on 9/7/2020.
 * 
 * This class is a Custom Provider used for better stack trace readability.
 */
public class CustomStackTraceJsonProvider extends StackTraceJsonProvider {

	public CustomStackTraceJsonProvider() {
		super();
	}

	@Override
    public void writeTo(JsonGenerator generator, ILoggingEvent event) {
		IThrowableProxy throwableProxy = event.getThrowableProxy();
		if (throwableProxy != null) {
			String msg = getThrowableConverter().convert(event);
			String[] lines = msg.split("\\n\\t");
			JsonWritingUtils.writeStringArrayField(generator, getFieldName(), lines);
		}
	}
}