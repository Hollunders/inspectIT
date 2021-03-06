package rocks.inspectit.agent.java.sensor.method.timer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import rocks.inspectit.agent.java.AbstractLogSupport;
import rocks.inspectit.agent.java.config.IPropertyAccessor;
import rocks.inspectit.agent.java.config.impl.RegisteredSensorConfig;
import rocks.inspectit.agent.java.core.ICoreService;
import rocks.inspectit.agent.java.core.IObjectStorage;
import rocks.inspectit.agent.java.core.IPlatformManager;
import rocks.inspectit.agent.java.core.IdNotAvailableException;
import rocks.inspectit.agent.java.util.Timer;
import rocks.inspectit.shared.all.communication.DefaultData;
import rocks.inspectit.shared.all.communication.data.TimerData;
import rocks.inspectit.shared.all.communication.valueobject.TimerRawVO;
import rocks.inspectit.shared.all.util.ObjectUtils;

@SuppressWarnings("PMD")
public class TimerHookTest extends AbstractLogSupport {

	@Mock
	private Timer timer;

	@Mock
	private IPlatformManager platformManager;

	@Mock
	private IPropertyAccessor propertyAccessor;

	@Mock
	private ICoreService coreService;

	@Mock
	private RegisteredSensorConfig registeredSensorConfig;

	@Mock
	private ThreadMXBean threadMXBean;

	private TimerHook timerHook;

	@BeforeMethod
	public void initTestClass() {
		Map<String, Object> settings = new HashMap<String, Object>();
		settings.put("mode", "raw");
		when(threadMXBean.isThreadCpuTimeEnabled()).thenReturn(true);
		when(threadMXBean.isThreadCpuTimeSupported()).thenReturn(true);
		timerHook = new TimerHook(timer, platformManager, propertyAccessor, settings, threadMXBean);
	}

	@Test
	public void sameMethodTwice() throws IdNotAvailableException {
		// set up data
		long platformId = 1L;
		long methodId = 3L;
		long sensorTypeId = 11L;
		Object object = mock(Object.class);
		Object[] parameters = new Object[0];
		Object result = mock(Object.class);

		Double firstTimerValue = 1000.0d;
		Double secondTimerValue = 1323.0d;
		Double thirdTimerValue = 1894.0d;
		Double fourthTimerValue = 2812.0d;

		when(timer.getCurrentTime()).thenReturn(firstTimerValue).thenReturn(secondTimerValue).thenReturn(thirdTimerValue).thenReturn(fourthTimerValue);
		when(platformManager.getPlatformId()).thenReturn(platformId);
		when(registeredSensorConfig.getSettings()).thenReturn(Collections.<String, Object> singletonMap("charting", Boolean.TRUE));

		// First call
		timerHook.beforeBody(methodId, sensorTypeId, object, parameters, registeredSensorConfig);
		verify(timer, times(1)).getCurrentTime();

		timerHook.firstAfterBody(methodId, sensorTypeId, object, parameters, result, registeredSensorConfig);
		verify(timer, times(2)).getCurrentTime();

		timerHook.secondAfterBody(coreService, methodId, sensorTypeId, object, parameters, result, registeredSensorConfig);
		verify(platformManager).getPlatformId();
		verify(coreService).getObjectStorage(sensorTypeId, methodId, null);
		verify(registeredSensorConfig).isPropertyAccess();

		PlainTimerStorage plainTimerStorage = new PlainTimerStorage(null, platformId, sensorTypeId, methodId, null, true);
		plainTimerStorage.addData(secondTimerValue - firstTimerValue, 0.0d);
		verify(coreService).addObjectStorage(eq(sensorTypeId), eq(methodId), (String) eq(null), argThat(new PlainTimerStorageVerifier(plainTimerStorage)));

		// second one
		timerHook.beforeBody(methodId, sensorTypeId, object, parameters, registeredSensorConfig);
		verify(timer, times(3)).getCurrentTime();

		timerHook.firstAfterBody(methodId, sensorTypeId, object, parameters, result, registeredSensorConfig);
		verify(timer, times(4)).getCurrentTime();

		when(coreService.getObjectStorage(sensorTypeId, methodId, null)).thenReturn(plainTimerStorage);
		timerHook.secondAfterBody(coreService, methodId, sensorTypeId, object, parameters, result, registeredSensorConfig);
		verify(coreService, times(2)).getObjectStorage(sensorTypeId, methodId, null);
		verify(registeredSensorConfig, times(2)).isPropertyAccess();
		verify(registeredSensorConfig, times(1)).getSettings();

		TimerRawVO timerRawVO = (TimerRawVO) plainTimerStorage.finalizeDataObject();
		assertThat(timerRawVO.getPlatformIdent(), is(equalTo(platformId)));
		assertThat(timerRawVO.getMethodIdent(), is(equalTo(methodId)));
		assertThat(timerRawVO.getSensorTypeIdent(), is(equalTo(sensorTypeId)));
		assertThat(timerRawVO.getData().get(0).getData()[0], is(equalTo(secondTimerValue - firstTimerValue)));
		assertThat(timerRawVO.getData().get(0).getData()[1], is(equalTo(fourthTimerValue - thirdTimerValue)));

		verifyNoMoreInteractions(timer, platformManager, coreService, registeredSensorConfig);
		verifyZeroInteractions(propertyAccessor, object, result);
	}

	/**
	 * Inner class used to verify the contents of PlainTimerData objects.
	 */
	private static class PlainTimerStorageVerifier extends ArgumentMatcher<PlainTimerStorage> {
		private final ITimerStorage timerStorage;

		public PlainTimerStorageVerifier(PlainTimerStorage timerStorage) {
			this.timerStorage = timerStorage;
		}

		@Override
		public boolean matches(Object object) {
			if (!PlainTimerStorage.class.isInstance(object)) {
				return false;
			}
			PlainTimerStorage otherPlainTimerStorage = (PlainTimerStorage) object;
			// we receive the raw vo by calling finalize on the object storage!
			TimerRawVO timerRawVO = (TimerRawVO) timerStorage.finalizeDataObject();
			TimerRawVO otherTimerRawVO = (TimerRawVO) otherPlainTimerStorage.finalizeDataObject();
			if (timerRawVO.getPlatformIdent() != otherTimerRawVO.getPlatformIdent()) {
				return false;
			} else if (timerRawVO.getMethodIdent() != otherTimerRawVO.getMethodIdent()) {
				return false;
			} else if (timerRawVO.getSensorTypeIdent() != otherTimerRawVO.getSensorTypeIdent()) {
				return false;
			} else if (!ObjectUtils.equals(timerRawVO.getParameterContentData(), otherTimerRawVO.getParameterContentData())) {
				return false;
			} else if (!timerRawVO.getData().equals(otherTimerRawVO.getData())) {
				return false;
			}
			return true;
		}
	}

	@Test
	public void platformIdNotAvailable() throws IdNotAvailableException {
		// set up data
		long methodId = 3L;
		long sensorTypeId = 11L;
		Object object = mock(Object.class);
		Object[] parameters = new Object[0];
		Object result = mock(Object.class);

		Double firstTimerValue = 1000.453d;
		Double secondTimerValue = 1323.675d;

		when(timer.getCurrentTime()).thenReturn(firstTimerValue).thenReturn(secondTimerValue);
		doThrow(new IdNotAvailableException("")).when(platformManager).getPlatformId();

		timerHook.beforeBody(methodId, sensorTypeId, object, parameters, registeredSensorConfig);
		timerHook.firstAfterBody(methodId, sensorTypeId, object, parameters, result, registeredSensorConfig);
		timerHook.secondAfterBody(coreService, methodId, sensorTypeId, object, parameters, result, registeredSensorConfig);

		verify(coreService, never()).addObjectStorage(anyLong(), anyLong(), anyString(), (IObjectStorage) isNull());
	}

	@Test
	public void propertyAccess() throws IdNotAvailableException {
		// set up data
		long platformId = 1L;
		long methodId = 3L;
		long sensorTypeId = 11L;
		Object object = mock(Object.class);
		Object[] parameters = new Object[2];
		Object result = mock(Object.class);

		Double firstTimerValue = 1000.453d;
		Double secondTimerValue = 1323.675d;

		when(timer.getCurrentTime()).thenReturn(firstTimerValue).thenReturn(secondTimerValue);
		when(platformManager.getPlatformId()).thenReturn(platformId);
		when(registeredSensorConfig.isPropertyAccess()).thenReturn(true);

		timerHook.beforeBody(methodId, sensorTypeId, object, parameters, registeredSensorConfig);
		timerHook.firstAfterBody(methodId, sensorTypeId, object, parameters, result, registeredSensorConfig);
		timerHook.secondAfterBody(coreService, methodId, sensorTypeId, object, parameters, result, registeredSensorConfig);

		verify(registeredSensorConfig, times(1)).isPropertyAccess();
		verify(propertyAccessor, times(1)).getParameterContentData(registeredSensorConfig.getPropertyAccessorList(), object, parameters, result);
	}

	@Test
	public void charting() throws IdNotAvailableException {
		// set up data
		long platformId = 1L;
		long methodId = 3L;
		long sensorTypeId = 11L;
		Object object = mock(Object.class);
		Object[] parameters = new Object[2];
		Object result = mock(Object.class);

		Double firstTimerValue = 1000.453d;
		Double secondTimerValue = 1323.675d;

		when(timer.getCurrentTime()).thenReturn(firstTimerValue).thenReturn(secondTimerValue);
		when(platformManager.getPlatformId()).thenReturn(platformId);
		when(registeredSensorConfig.getSettings()).thenReturn(Collections.<String, Object> singletonMap("charting", Boolean.TRUE));

		timerHook.beforeBody(methodId, sensorTypeId, object, parameters, registeredSensorConfig);
		timerHook.firstAfterBody(methodId, sensorTypeId, object, parameters, result, registeredSensorConfig);
		timerHook.secondAfterBody(coreService, methodId, sensorTypeId, object, parameters, result, registeredSensorConfig);

		ArgumentCaptor<IObjectStorage> capture = ArgumentCaptor.forClass(IObjectStorage.class);
		verify(coreService).addObjectStorage(eq(sensorTypeId), eq(methodId), anyString(), capture.capture());
		DefaultData finalizedDataObject = capture.getValue().finalizeDataObject();
		assertThat(finalizedDataObject, is(instanceOf(TimerRawVO.class)));
		TimerData timerData = (TimerData) ((TimerRawVO) finalizedDataObject).finalizeData();
		assertThat(timerData.isCharting(), is(true));
	}

	@Test
	public void aggregateStorage() throws IdNotAvailableException {
		Map<String, Object> settings = new HashMap<String, Object>();
		settings.put("mode", "aggregate");
		timerHook = new TimerHook(timer, platformManager, propertyAccessor, settings, ManagementFactory.getThreadMXBean());

		// set up data
		long platformId = 1L;
		long methodId = 3L;
		long sensorTypeId = 11L;
		Object object = mock(Object.class);
		Object[] parameters = new Object[0];
		Object result = mock(Object.class);

		Double firstTimerValue = 1000.453d;
		Double secondTimerValue = 1323.675d;

		when(timer.getCurrentTime()).thenReturn(firstTimerValue).thenReturn(secondTimerValue);
		when(platformManager.getPlatformId()).thenReturn(platformId);

		when(registeredSensorConfig.getSettings()).thenReturn(Collections.<String, Object> singletonMap("charting", Boolean.TRUE));

		timerHook.beforeBody(methodId, sensorTypeId, object, parameters, registeredSensorConfig);
		verify(timer, times(1)).getCurrentTime();

		timerHook.firstAfterBody(methodId, sensorTypeId, object, parameters, result, registeredSensorConfig);
		verify(timer, times(2)).getCurrentTime();

		timerHook.secondAfterBody(coreService, methodId, sensorTypeId, object, parameters, result, registeredSensorConfig);
		verify(platformManager).getPlatformId();
		verify(coreService).getObjectStorage(sensorTypeId, methodId, null);
		verify(registeredSensorConfig).isPropertyAccess();
		verify(registeredSensorConfig).getSettings();

		AggregateTimerStorage aggregateTimerStorage = new AggregateTimerStorage(null, platformId, sensorTypeId, methodId, null, true);
		aggregateTimerStorage.addData(secondTimerValue - firstTimerValue, -1.0d);
		verify(coreService).addObjectStorage(eq(sensorTypeId), eq(methodId), (String) eq(null), argThat(new AggregateTimerStorageVerifier(aggregateTimerStorage)));

		verifyNoMoreInteractions(timer, platformManager, coreService, registeredSensorConfig);
		verifyZeroInteractions(propertyAccessor, object, result);
	}

	/**
	 * Inner class used to verify the contents of AggregateTimerStorage objects.
	 */
	private static class AggregateTimerStorageVerifier extends ArgumentMatcher<AggregateTimerStorage> {
		private final ITimerStorage timerStorage;

		public AggregateTimerStorageVerifier(AggregateTimerStorage timerStorage) {
			this.timerStorage = timerStorage;
		}

		@Override
		public boolean matches(Object object) {
			if (!AggregateTimerStorage.class.isInstance(object)) {
				return false;
			}

			try {
				AggregateTimerStorage otherAggregateTimerStorage = (AggregateTimerStorage) object;
				// we have to use reflection here
				Field field = AggregateTimerStorage.class.getDeclaredField("timerRawVO");
				field.setAccessible(true);
				TimerRawVO timerRawVO = (TimerRawVO) field.get(timerStorage);
				TimerRawVO otherTimerRawVO = (TimerRawVO) field.get(otherAggregateTimerStorage);
				if (timerRawVO.getPlatformIdent() != otherTimerRawVO.getPlatformIdent()) {
					return false;
				} else if (timerRawVO.getMethodIdent() != otherTimerRawVO.getMethodIdent()) {
					return false;
				} else if (timerRawVO.getSensorTypeIdent() != otherTimerRawVO.getSensorTypeIdent()) {
					return false;
				} else if (!ObjectUtils.equals(timerRawVO.getParameterContentData(), otherTimerRawVO.getParameterContentData())) {
					return false;
				}
				return true;
			} catch (Exception exception) {
				exception.printStackTrace();
				return false;
			}
		}
	}

	@Test
	public void optimizedStorage() throws IdNotAvailableException {
		Map<String, Object> settings = new HashMap<String, Object>();
		settings.put("mode", "optimized");
		timerHook = new TimerHook(timer, platformManager, propertyAccessor, settings, ManagementFactory.getThreadMXBean());

		// set up data
		long platformId = 1L;
		long methodId = 3L;
		long sensorTypeId = 11L;
		Object object = mock(Object.class);
		Object[] parameters = new Object[0];
		Object result = mock(Object.class);

		Double firstTimerValue = 1000.453d;
		Double secondTimerValue = 1323.675d;

		when(timer.getCurrentTime()).thenReturn(firstTimerValue).thenReturn(secondTimerValue);
		when(platformManager.getPlatformId()).thenReturn(platformId);

		when(registeredSensorConfig.getSettings()).thenReturn(Collections.<String, Object> singletonMap("charting", Boolean.TRUE));

		timerHook.beforeBody(methodId, sensorTypeId, object, parameters, registeredSensorConfig);
		verify(timer, times(1)).getCurrentTime();

		timerHook.firstAfterBody(methodId, sensorTypeId, object, parameters, result, registeredSensorConfig);
		verify(timer, times(2)).getCurrentTime();

		timerHook.secondAfterBody(coreService, methodId, sensorTypeId, object, parameters, result, registeredSensorConfig);
		verify(platformManager).getPlatformId();
		verify(coreService).getObjectStorage(sensorTypeId, methodId, null);
		verify(registeredSensorConfig).isPropertyAccess();
		verify(registeredSensorConfig).getSettings();

		OptimizedTimerStorage optimizedTimerStorage = new OptimizedTimerStorage(null, platformId, sensorTypeId, methodId, null, true);
		optimizedTimerStorage.addData(secondTimerValue - firstTimerValue, -1.0d);
		verify(coreService).addObjectStorage(eq(sensorTypeId), eq(methodId), (String) eq(null), argThat(new OptimizedTimerStorageVerifier(optimizedTimerStorage)));

		verifyNoMoreInteractions(timer, platformManager, coreService, registeredSensorConfig);
		verifyZeroInteractions(propertyAccessor, object, result);
	}

	/**
	 * Inner class used to verify the contents of AggregateTimerStorage objects.
	 */
	private static class OptimizedTimerStorageVerifier extends ArgumentMatcher<OptimizedTimerStorage> {
		private final ITimerStorage timerStorage;

		public OptimizedTimerStorageVerifier(OptimizedTimerStorage timerStorage) {
			this.timerStorage = timerStorage;
		}

		@Override
		public boolean matches(Object object) {
			if (!OptimizedTimerStorage.class.isInstance(object)) {
				return false;
			}
			TimerData timerData = (TimerData) timerStorage.finalizeDataObject();
			TimerData otherTimerData = (TimerData) ((OptimizedTimerStorage) object).finalizeDataObject();
			if (timerData.getPlatformIdent() != otherTimerData.getPlatformIdent()) {
				return false;
			} else if (timerData.getMethodIdent() != otherTimerData.getMethodIdent()) {
				return false;
			} else if (timerData.getSensorTypeIdent() != otherTimerData.getSensorTypeIdent()) {
				return false;
			} else if (timerData.getCount() != otherTimerData.getCount()) {
				return false;
			} else if (timerData.getDuration() != otherTimerData.getDuration()) {
				return false;
			} else if (timerData.getMax() != otherTimerData.getMax()) {
				return false;
			} else if (timerData.getMin() != otherTimerData.getMin()) {
				return false;
			} else if (timerData.getAverage() != otherTimerData.getAverage()) {
				return false;
			} else if (!ObjectUtils.equals(timerData.getParameterContentData(), otherTimerData.getParameterContentData())) {
				return false;
			} else if (timerData.getVariance() != otherTimerData.getVariance()) {
				return false;
			}
			return true;
		}
	}

	@Test
	public void oneRecordWithCpuTime() throws IdNotAvailableException {
		// set up data
		long platformId = 1L;
		long methodId = 3L;
		long sensorTypeId = 11L;
		Object object = mock(Object.class);
		Object[] parameters = new Object[0];
		Object result = mock(Object.class);

		Double firstTimerValue = 1000.453d;
		Double secondTimerValue = 1323.675d;

		Long firstCpuTimerValue = 5000L;
		Long secondCpuTimerValue = 6872L;

		when(timer.getCurrentTime()).thenReturn(firstTimerValue).thenReturn(secondTimerValue);
		when(threadMXBean.getCurrentThreadCpuTime()).thenReturn(firstCpuTimerValue).thenReturn(secondCpuTimerValue);
		when(platformManager.getPlatformId()).thenReturn(platformId);

		when(registeredSensorConfig.getSettings()).thenReturn(Collections.<String, Object> singletonMap("charting", Boolean.TRUE));

		timerHook.beforeBody(methodId, sensorTypeId, object, parameters, registeredSensorConfig);
		verify(timer, times(1)).getCurrentTime();

		timerHook.firstAfterBody(methodId, sensorTypeId, object, parameters, result, registeredSensorConfig);
		verify(timer, times(2)).getCurrentTime();

		timerHook.secondAfterBody(coreService, methodId, sensorTypeId, object, parameters, result, registeredSensorConfig);
		verify(platformManager).getPlatformId();
		verify(coreService).getObjectStorage(sensorTypeId, methodId, null);
		verify(registeredSensorConfig).isPropertyAccess();
		verify(registeredSensorConfig).getSettings();

		PlainTimerStorage plainTimerStorage = new PlainTimerStorage(null, platformId, sensorTypeId, methodId, null, true);
		plainTimerStorage.addData(secondTimerValue - firstTimerValue, (secondCpuTimerValue - firstCpuTimerValue) / 1000000.0d);
		verify(coreService).addObjectStorage(eq(sensorTypeId), eq(methodId), (String) eq(null), argThat(new PlainTimerStorageVerifier(plainTimerStorage)));

		verifyNoMoreInteractions(timer, platformManager, coreService, registeredSensorConfig);
		verifyZeroInteractions(propertyAccessor, object, result);
	}

	@Test
	public void twoRecordsWithCpuTime() throws IdNotAvailableException {
		long platformId = 1L;
		long methodIdOne = 3L;
		long methodIdTwo = 9L;
		long sensorTypeId = 11L;
		Object object = mock(Object.class);
		Object[] parameters = new Object[0];
		Object result = mock(Object.class);

		Double firstTimerValue = 1000.453d;
		Double secondTimerValue = 1323.675d;
		Double thirdTimerValue = 1578.92d;
		Double fourthTimerValue = 2319.712d;

		Long firstCpuTimerValue = 5000L;
		Long secondCpuTimerValue = 6872L;
		Long thirdCpuTimerValue = 8412L;
		Long fourthCpuTimerValue = 15932L;

		when(timer.getCurrentTime()).thenReturn(firstTimerValue).thenReturn(secondTimerValue).thenReturn(thirdTimerValue).thenReturn(fourthTimerValue);
		when(threadMXBean.getCurrentThreadCpuTime()).thenReturn(firstCpuTimerValue).thenReturn(secondCpuTimerValue).thenReturn(thirdCpuTimerValue).thenReturn(fourthCpuTimerValue);
		when(platformManager.getPlatformId()).thenReturn(platformId);

		timerHook.beforeBody(methodIdOne, sensorTypeId, object, parameters, registeredSensorConfig);
		timerHook.beforeBody(methodIdTwo, sensorTypeId, object, parameters, registeredSensorConfig);

		timerHook.firstAfterBody(methodIdTwo, sensorTypeId, object, parameters, result, registeredSensorConfig);
		timerHook.secondAfterBody(coreService, methodIdTwo, sensorTypeId, object, parameters, result, registeredSensorConfig);
		PlainTimerStorage plainTimerStorageTwo = new PlainTimerStorage(null, platformId, sensorTypeId, methodIdTwo, null, true);
		plainTimerStorageTwo.addData(thirdTimerValue - secondTimerValue, (thirdCpuTimerValue - secondCpuTimerValue) / 1000000.0d);
		verify(coreService).addObjectStorage(eq(sensorTypeId), eq(methodIdTwo), (String) eq(null), argThat(new PlainTimerStorageVerifier(plainTimerStorageTwo)));

		timerHook.firstAfterBody(methodIdOne, sensorTypeId, object, parameters, result, registeredSensorConfig);
		timerHook.secondAfterBody(coreService, methodIdOne, sensorTypeId, object, parameters, result, registeredSensorConfig);
		PlainTimerStorage plainTimerStorageOne = new PlainTimerStorage(null, platformId, sensorTypeId, methodIdOne, null, true);
		plainTimerStorageOne.addData(fourthTimerValue - firstTimerValue, (fourthCpuTimerValue - firstCpuTimerValue) / 1000000.0d);
		verify(coreService).addObjectStorage(eq(sensorTypeId), eq(methodIdOne), (String) eq(null), argThat(new PlainTimerStorageVerifier(plainTimerStorageOne)));
	}

}
