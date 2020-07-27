/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
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
package org.openhab.binding.warmup.internal.handler;

import static org.openhab.binding.warmup.internal.WarmupBindingConstants.*;

import javax.measure.quantity.Temperature;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.QuantityType;
import org.eclipse.smarthome.core.library.unit.SIUnits;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.types.Command;
import org.openhab.binding.warmup.internal.model.query.LocationDTO;
import org.openhab.binding.warmup.internal.model.query.QueryResponseDTO;
import org.openhab.binding.warmup.internal.model.query.RoomDTO;

/**
 * @author James Melville - Initial contribution
 */
@NonNullByDefault
public class RoomHandler extends WarmupThingHandler implements WarmupRefreshListener {

    private RoomConfigurationDTO config;

    public RoomHandler(Thing thing) {
        super(thing);
        config = getConfigAs(RoomConfigurationDTO.class);
    }

    @Override
    public void initialize() {
        super.initialize();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        super.handleCommand(channelUID, command);
        if (CHANNEL_TARGET_TEMPERATURE.equals(channelUID.getId()) && command instanceof QuantityType<?>) {
            setOverride((QuantityType<?>) command);
        }
        if (CHANNEL_FROST_PROTECTION_MODE.equals(channelUID.getId()) && command instanceof OnOffType) {
            toggleFrostProtectionMode((OnOffType) command);
        }
    }

    /**
     * Process device list and populate room properties, status and state
     *
     * @param domain Data model representing all devices
     */
    @Override
    public void onRefresh(@Nullable QueryResponseDTO domain) {
        if (domain != null) {
            for (LocationDTO location : domain.getData().getUser().getLocations()) {
                for (RoomDTO room : location.getRooms()) {
                    if (room.getThermostat4ies() != null && !room.getThermostat4ies().isEmpty()
                            && room.getThermostat4ies().get(0).getDeviceSN().equals(config.serialNumber)) {

                        updateStatus(ThingStatus.ONLINE);

                        updateProperty("Id", room.getId());
                        updateProperty("Serial Number", config.serialNumber);
                        updateProperty("Name", room.getName());
                        updateProperty("Location Id", location.getId());
                        updateProperty("Location", location.getName());

                        updateState(CHANNEL_CURRENT_TEMPERATURE, parseTemperature(room.getCurrentTemperature()));
                        updateState(CHANNEL_TARGET_TEMPERATURE, parseTemperature(room.getTargetTemperature()));
                        updateState(CHANNEL_OVERRIDE_DURATION, parseDuration(room.getOverrideDuration()));
                        updateState(CHANNEL_RUN_MODE, parseString(room.getRunMode()));
                        updateState(CHANNEL_FROST_PROTECTION_MODE,
                                OnOffType.from(room.getRunMode().equals(FROST_PROTECTION_MODE)));
                        return;
                    }
                }
            }
        }
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Room not found");
    }

    private void setOverride(final QuantityType<?> command) {
        @SuppressWarnings("unchecked")
        final QuantityType<Temperature> value = (QuantityType<Temperature>) command.toUnit(SIUnits.CELSIUS);

        String roomId = getThing().getProperties().get("Id");
        String locationId = getThing().getProperties().get("Location Id");
        if (value != null && bridgeHandler != null) {
            bridgeHandler.getApi().setOverride(locationId, roomId, value, config.overrideDurationMin);
            bridgeHandler.refreshFromServer();
        }
    }

    private void toggleFrostProtectionMode(OnOffType command) {

        String roomId = getThing().getProperties().get("Id");
        String locationId = getThing().getProperties().get("Location Id");
        if (bridgeHandler != null) {
            bridgeHandler.getApi().toggleFrostProtectionMode(locationId, roomId, command);
            bridgeHandler.refreshFromServer();
        }
    }
}
