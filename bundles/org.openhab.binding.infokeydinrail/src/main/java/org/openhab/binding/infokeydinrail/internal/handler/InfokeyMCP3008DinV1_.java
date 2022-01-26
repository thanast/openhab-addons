/**
 * Copyright (c) 2010-2022 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.infokeydinrail.internal.handler;

import static org.openhab.binding.infokeydinrail.internal.InfokeyBindingConstants.*;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.openhab.binding.infokeydinrail.internal.GPIODataHolder;
import org.openhab.binding.infokeydinrail.internal.MCP3008PinMapper;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pi4j.gpio.extension.base.AdcGpioProvider;
import com.pi4j.gpio.extension.mcp.MCP3008GpioProvider;
import com.pi4j.io.gpio.GpioPinAnalogInput;
import com.pi4j.io.gpio.GpioPinDigitalOutput;
import com.pi4j.io.gpio.Pin;
import com.pi4j.io.gpio.PinState;
import com.pi4j.io.gpio.event.GpioPinAnalogValueChangeEvent;
import com.pi4j.io.gpio.event.GpioPinListenerAnalog;
import com.pi4j.io.spi.SpiChannel;

/**
 * The {@link Infokey2CoilRelayDinV1Handler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * This GPIO provider implements the MCP3008 10-Bit Analog-to-Digital Converter (ADC) as native Pi4J GPIO pins.
 * </p>
 *
 * <p>
 * The MCP3008 is connected via SPI connection to the Raspberry Pi and provides 8 GPIO analog input pins.
 * </p>
 *
 * @author Themistoklis Anastasopoulos - Initial contribution
 */
// @NonNullByDefault
public class InfokeyMCP3008DinV1_ extends BaseThingHandler implements GpioPinListenerAnalog {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    // final AdcGpioProvider provider = new MCP3008GpioProvider(SpiChannel.CS0);
    private AdcGpioProvider mcpProvider;
    private Integer spiChannel;
    private Integer chipSelect;
    private InfokeyAnalogPinStateHolder pinStateHolder;
    private Map<ChannelUID, ScheduledFuture<?>> pollingJobMap;
    private GpioPinDigitalOutput pinCS;
    private Integer pollingInterval = 5000;
    private Integer minValueChange = Integer.parseInt(DEFAULT_MIN_VALUE_CHANGE);
    private String valueRendererType = DEFAULT_VALUE_RENDER_TYPE;
    private Map<ChannelUID, Double> channelsPrevValues = new HashMap<>();
    private DecimalFormat df = new DecimalFormat("#.##");

    public InfokeyMCP3008DinV1_(Thing thing) {
        super(thing);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // TODO Auto-generated method stub
    }

    @Override
    public void initialize() {
        try {
            checkConfiguration();
            mcpProvider = initializeMcpProvider();
            pinStateHolder = new InfokeyAnalogPinStateHolder(this.thing);
            updateStatus(ThingStatus.ONLINE);
        } catch (IllegalArgumentException | SecurityException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "An exception occurred while adding pin. Check pin configuration. Exception: " + e.getMessage());
        }
    }

    private boolean verifyChannel(ChannelUID channelUID) {
        if (!isChannelGroupValid(channelUID) || !isChannelValid(channelUID)) {
            logger.warn("Channel group or channel is invalid. Probably configuration problem");
            return false;
        }
        return true;
    }

    private boolean isChannelGroupValid(ChannelUID channelUID) {
        if (!channelUID.isInGroup()) {
            logger.debug("Defined channel not in group: {}", channelUID.getAsString());
            return false;
        }
        boolean channelGroupValid = SUPPORTED_CHANNEL_GROUPS.contains(channelUID.getGroupId());
        logger.debug("Defined channel in group: {}. Valid: {}", channelUID.getGroupId(), channelGroupValid);

        return channelGroupValid;
    }

    private boolean isChannelValid(ChannelUID channelUID) {
        boolean channelValid = MCP3008_SUPPORTED_CHANNELS.contains(channelUID.getIdWithoutGroup());
        logger.debug("Is channel {} in supported channels: {}", channelUID.getIdWithoutGroup(), channelValid);
        return channelValid;
    }

    protected void checkConfiguration() {
        Configuration configuration = getConfig();
        spiChannel = Integer.parseInt((configuration.get(SPI_CHANNEL)).toString());
        chipSelect = Integer.parseInt((configuration.get(SPI_CHIP_SELECT)).toString());
        pollingInterval = Integer.parseInt((configuration.get(POLLING_INTERVAL)).toString());
    }

    private MCP3008GpioProvider initializeMcpProvider() {
        MCP3008GpioProvider mcp = null;
        logger.debug("initializing infokey provider for SPI Channel {} and chipSelect {}", spiChannel, chipSelect);
        try {
            // Create custom MCP3008 analog gpio provider
            // we must specify which chip select (CS) that that ADC chip is physically connected to.
            mcp = new MCP3008GpioProvider((spiChannel == 0 ? SpiChannel.CS0 : SpiChannel.CS1));
        } catch (IOException ex) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Unexpected Error: " + ex.getMessage());
        }

        if (spiChannel == 1 && chipSelect != 0) {
            pinCS = GPIODataHolder.GPIO.provisionDigitalOutputPin(MCP3008PinMapper.getCS(chipSelect), "CS",
                    PinState.HIGH);
            pollingJobMap = new HashMap<ChannelUID, ScheduledFuture<?>>();
        }

        logger.debug("got mcpProvider {}", mcp);
        return mcp;
    }

    private GpioPinAnalogInput initializeInputAnalogPin(ChannelUID channel) {
        logger.debug("initializing input pin for channel {}", channel.getAsString());
        Pin pin = MCP3008PinMapper.get(channel.getIdWithoutGroup());

        logger.debug("initializing pin {}, mcpProvider {}, minValueChange {}, valueRendererType {}", pin, mcpProvider,
                minValueChange, valueRendererType);

        // renderTypeMap.put(channel, valueRendererType);

        GpioPinAnalogInput input = GPIODataHolder.GPIO.provisionAnalogInputPin(mcpProvider, pin);

        mcpProvider.setEventThreshold(minValueChange, input);

        if (spiChannel == 0) {
            mcpProvider.setMonitorEnabled(true);
            mcpProvider.setMonitorInterval(pollingInterval);
            input.addListener(this);
        } else {
            mcpProvider.setMonitorEnabled(false);

            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    pinCS.setState(PinState.LOW);
                    readAnalogInputPinState(input.getValue(), channel);
                    pinCS.setState(PinState.HIGH);
                }
            };

            pollingJobMap.put(channel,
                    scheduler.scheduleAtFixedRate(runnable, 0, pollingInterval, TimeUnit.MILLISECONDS));
        }
        logger.debug("Bound analog input for PIN: {}, ItemName: {}, ", pin, channel.getAsString());

        logger.debug("updating channel {} with value {} ", channel, input.getValue());

        readAnalogInputPinState(input.getValue(), channel);

        return input;
    }

    @Override
    public void dispose() {
        final InfokeyAnalogPinStateHolder holder = pinStateHolder;

        if (holder != null) {
            holder.unBindGpioPins();
        }

        super.dispose();
    }

    @Override
    public void handleGpioPinAnalogValueChangeEvent(GpioPinAnalogValueChangeEvent event) {
        GpioPinAnalogInput inputEventPin = ((GpioPinAnalogInput) event.getPin());
        logger.debug(" --> GPIO PIN NO {} VALUE CHANGE: {} = {}", inputEventPin.getPin(), inputEventPin,
                event.getValue());
        ChannelUID channelForPin = pinStateHolder.getChannelUIDFromInputPin(inputEventPin);
        if (channelForPin != null) {

            Integer mvc = getMinValueChange(channelForPin);
            Boolean update = false;

            if (channelsPrevValues.containsKey(channelForPin)) {
                double p = channelsPrevValues.get(channelForPin);

                if ((event.getValue() > mvc + p) && (event.getValue() < mvc - p)) {
                    update = true;
                    channelsPrevValues.put(channelForPin, event.getValue());
                }
            } else {
                update = true;
                channelsPrevValues.put(channelForPin, event.getValue());
            }

            if (update) {
                readAnalogInputPinState(event.getValue(), channelForPin);
            }

        } else {
            logger.debug("Ops! getChannelUIDFromInputPin is null");
        }
    }

    private void readAnalogInputPinState(double pinValue, ChannelUID channelForPin) {
        String result = "";

        try {

            logger.debug("updating channel {} with value {} ", channelForPin, String.valueOf(pinValue));

            if (getChannelRenderType(channelForPin).equalsIgnoreCase("VOLTS_5.1")) {
                result = df.format((pinValue * (5.0 / 1023.0)) / (4.7 / (8.2 + 4.7))) + " V";
            } else if (getChannelRenderType(channelForPin).equalsIgnoreCase("VOLTS_3.1")) {
                result = df.format((pinValue * (3.3 / 1023.0)) / (4.7 / (12 + 4.7))) + " V";
            } else if (getChannelRenderType(channelForPin).equalsIgnoreCase("VOLTS_3.2")) {
                result = df.format((pinValue * (3.3 / 1023.0)) / (2.7 / (8.2 + 2.7))) + " V";
            } else if (getChannelRenderType(channelForPin).equalsIgnoreCase("VOLTS_3.3")) {
                result = df.format((pinValue * (3.3 / 1023.0)) / (4.7 / (8.2 + 4.7))) + " V";
            } else if (getChannelRenderType(channelForPin).equalsIgnoreCase("ZMPT101B")) {
                double chVolt = (pinValue * (5.0 / 1023.0)) / (4.7 / (8.2 + 4.7));
                int voltLevels = (int) (chVolt / 5.0 * 1023);
                logger.debug("ZMPT101B  chvolt {} / levels {}", chVolt, voltLevels);
                result = df.format(((((voltLevels / Math.sqrt(2)) - 420.76) / -90.24) * -210.2) + 210.2) + " V";
            } else {
                result = String.valueOf(pinValue);
            }

        } catch (Exception ex) {
            logger.debug("Ops!", ex);
            result = "0.00";
        }

        updateState(channelForPin, new StringType(result));
    }

    private String getChannelRenderType(ChannelUID channel) {
        String renderType = "RAW";
        if (channel != null) {
            try {
                Configuration configuration = thing.getChannel(channel).getConfiguration();

                renderType = (configuration.get(VALUE_RENDERER_TYPE).toString()) != null
                        ? ((String) configuration.get(VALUE_RENDERER_TYPE).toString())
                        : DEFAULT_VALUE_RENDER_TYPE;
            } catch (Exception ex) {
                logger.debug("Ops!", ex);
                renderType = "RAW";
            }
        }

        return renderType;
    }

    private Integer getMinValueChange(ChannelUID channel) {
        Integer mvc = minValueChange;
        if (channel != null) {
            try {
                Configuration configuration = thing.getChannel(channel).getConfiguration();

                mvc = (configuration.get(MIN_VALUE_CHANGE).toString()) != null
                        ? Integer.parseInt(configuration.get(MIN_VALUE_CHANGE).toString())
                        : minValueChange;
            } catch (Exception ex) {
                logger.debug("Ops!", ex);
                mvc = minValueChange;
            }
        }

        return mvc;
    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        synchronized (this) {
            logger.debug("channel linked {}", channelUID.getAsString());
            if (!verifyChannel(channelUID)) {
                return;
            }
            String channelGroup = channelUID.getGroupId();

            if (channelGroup != null && channelGroup.equals(CHANNEL_GROUP_ANALOG_INPUT)) {
                if (pinStateHolder.getInputPin(channelUID) != null) {
                    return;
                }
                GpioPinAnalogInput inputPin = initializeInputAnalogPin(channelUID);

                pinStateHolder.addInputPin(inputPin, channelUID);

            }
            super.channelLinked(channelUID);
        }
    }
}
