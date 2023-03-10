package ru.home.atmosphere.processing.temperature;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.home.atmosphere.atmosphere_metrics.Temperature;
import ru.home.atmosphere.log.MetricsLogWriter;
import ru.home.atmosphere.processing.ProcessingException;
import ru.home.atmosphere.processing.temperature.heater_relay.HeaterRelay;
import ru.home.atmosphere.processing.temperature.heater_relay.RelayException;

import java.sql.Timestamp;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TemperatureProcessingTests {

    private String sensor1Id = "s1";
    private String sensor2Id = "s2";
    private String sensor3Id = "s3";
    private Temperature temperature1 = new Temperature();
    private Temperature temperature2 = new Temperature();
    private Temperature temperature3 = new Temperature();
    private PriorityTemperature priorityTemperature;
    private HeaterRelay relay;
    private HeaterMode heaterMode;
    private MetricsLogWriter<TemperatureLogMessage> temperatureLog;
    private TemperatureProcessing processing;

    @BeforeEach
    public void init() {
        priorityTemperature = mock(PriorityTemperature.class);
        relay = mock(HeaterRelay.class);
        heaterMode = mock(HeaterMode.class);
        temperatureLog = mock(MetricsLogWriter.class);
        processing = new TemperatureProcessing(priorityTemperature, heaterMode, relay, temperatureLog);
    }

    private Map<String, Temperature> getMetrics() {
        temperature1.setValue(10.0f);
        temperature2.setValue(20.0f);
        temperature3.setValue(30.0f);
        return Map.of(sensor1Id, temperature1, sensor2Id, temperature2, sensor3Id, temperature3);
    }

    @Test
    public void process_errorWhileComputePriorityTemperature_exceptionThrown() throws ProcessingException {
        Map<String, Temperature> metrics = getMetrics();
        when(priorityTemperature.compute(metrics)).thenThrow(ProcessingException.class);

        assertThrows(ProcessingException.class, () -> processing.process(metrics));
        verify(priorityTemperature, times(1)).compute(metrics);
        verifyNoMoreInteractions(priorityTemperature, relay, heaterMode, temperatureLog);
    }

    @Test
    public void process_needToWarmUp_heaterEnabled() throws ProcessingException, RelayException {
        Map<String, Temperature> metrics = getMetrics();
        when(priorityTemperature.compute(metrics)).thenReturn(temperature1);
        when(heaterMode.isNeedWarmUp(temperature1.getValue())).thenReturn(true);

        processing.process(metrics);

        verify(priorityTemperature, times(1)).compute(metrics);
        verify(heaterMode, times(1)).isNeedWarmUp(temperature1.getValue());
        verify(relay, times(1)).switchOn();
        verify(temperatureLog, times(1)).log(any(List.class));
        verifyNoMoreInteractions(priorityTemperature, relay, heaterMode, temperatureLog);
    }

    @Test
    public void process_needToWarmUp_logMessagesAreCorrect() throws ProcessingException, RelayException {
        Map<String, Temperature> metrics = getMetrics();
        when(priorityTemperature.compute(metrics)).thenReturn(temperature1);
        when(heaterMode.isNeedWarmUp(temperature1.getValue())).thenReturn(true);
        ArgumentCaptor<List<TemperatureLogMessage>> logMessages = ArgumentCaptor.forClass(List.class);

        processing.process(metrics);

        verify(priorityTemperature, times(1)).compute(metrics);
        verify(heaterMode, times(1)).isNeedWarmUp(temperature1.getValue());
        verify(relay, times(1)).switchOn();
        verify(temperatureLog, times(1)).log(logMessages.capture());
        List<TemperatureLogMessage> loggedMessages = logMessages.getValue();
        assertEquals(metrics.size(), loggedMessages.size());
        Set<Timestamp> timestamps = new HashSet<>();
        for (TemperatureLogMessage logMessage : loggedMessages) {
            Temperature sourceTemperature = metrics.get(logMessage.getSensorId());
            assertEquals(sourceTemperature, logMessage.getMetric());
            assertTrue(logMessage.getHeaterRelayState());
            timestamps.add(logMessage.getMeasureTimestamp());
        }
        assertEquals(1, timestamps.size());//???????????????????? ?????? ?????????? ?????????????????????? ???????? timestamp
        verifyNoMoreInteractions(priorityTemperature, relay, heaterMode, temperatureLog);
    }

    @Test
    public void process_needCoolDown_heaterDisabled() throws ProcessingException, RelayException {
        Map<String, Temperature> metrics = getMetrics();
        when(priorityTemperature.compute(metrics)).thenReturn(temperature1);
        when(heaterMode.isNeedWarmUp(temperature1.getValue())).thenReturn(false);

        processing.process(metrics);

        verify(priorityTemperature, times(1)).compute(metrics);
        verify(heaterMode, times(1)).isNeedWarmUp(temperature1.getValue());
        verify(relay, times(1)).switchOff();
        verify(temperatureLog, times(1)).log(any(List.class));
        verifyNoMoreInteractions(priorityTemperature, relay, heaterMode, temperatureLog);
    }

    @Test
    public void process_needCoolDown_logMessagesAreCorrect() throws ProcessingException, RelayException {
        Map<String, Temperature> metrics = getMetrics();
        when(priorityTemperature.compute(metrics)).thenReturn(temperature1);
        when(heaterMode.isNeedWarmUp(temperature1.getValue())).thenReturn(false);
        ArgumentCaptor<List<TemperatureLogMessage>> logMessages = ArgumentCaptor.forClass(List.class);

        processing.process(metrics);

        verify(priorityTemperature, times(1)).compute(metrics);
        verify(heaterMode, times(1)).isNeedWarmUp(temperature1.getValue());
        verify(relay, times(1)).switchOff();
        verify(temperatureLog, times(1)).log(logMessages.capture());
        List<TemperatureLogMessage> loggedMessages = logMessages.getValue();
        assertEquals(metrics.size(), loggedMessages.size());
        Set<Timestamp> timestamps = new HashSet<>();
        for (TemperatureLogMessage logMessage : loggedMessages) {
            Temperature sourceTemperature = metrics.get(logMessage.getSensorId());
            assertEquals(sourceTemperature, logMessage.getMetric());
            assertFalse(logMessage.getHeaterRelayState());
            timestamps.add(logMessage.getMeasureTimestamp());
        }
        assertEquals(1, timestamps.size());//???????????????????? ?????? ?????????? ?????????????????????? ???????? timestamp
        verifyNoMoreInteractions(priorityTemperature, relay, heaterMode, temperatureLog);
    }

    @Test
    public void process_exceptionsChangeHeaterState_exceptionThrown() throws ProcessingException, RelayException {
        Map<String, Temperature> metrics = getMetrics();
        when(priorityTemperature.compute(metrics)).thenReturn(temperature1);
        when(heaterMode.isNeedWarmUp(temperature1.getValue())).thenReturn(true);
        RelayException relayException = new RelayException("test");
        doThrow(relayException).when(relay).switchOn();

        assertThrows(ProcessingException.class, () -> processing.process(metrics));
        verify(priorityTemperature, times(1)).compute(metrics);
        verify(heaterMode, times(1)).isNeedWarmUp(temperature1.getValue());
        verify(relay, times(1)).switchOn();
        verifyNoMoreInteractions(priorityTemperature, relay, heaterMode, temperatureLog);
    }

    @Test
    public void process_exceptionsWhileLog_exceptionThrown() throws ProcessingException, RelayException {
        Map<String, Temperature> metrics = getMetrics();
        when(priorityTemperature.compute(metrics)).thenReturn(temperature1);
        when(heaterMode.isNeedWarmUp(temperature1.getValue())).thenReturn(true);
        doThrow(RuntimeException.class).when(temperatureLog).log(any(List.class));

        assertThrows(ProcessingException.class, () -> processing.process(metrics));
        verify(priorityTemperature, times(1)).compute(metrics);
        verify(heaterMode, times(1)).isNeedWarmUp(temperature1.getValue());
        verify(relay, times(1)).switchOn();
        verify(temperatureLog, times(1)).log(any(List.class));
        verifyNoMoreInteractions(priorityTemperature, relay, heaterMode, temperatureLog);
    }
}
