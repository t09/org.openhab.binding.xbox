package org.openhab.binding.xbox.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The {@link XboxHandlerFactory} is responsible for creating things and thing
 * handlers.
 *
 * @author Jochen
 */
@NonNullByDefault
@Component(service = ThingHandlerFactory.class, immediate = true, configurationPid = "binding.xbox")
public class XboxHandlerFactory extends BaseThingHandlerFactory {

    private final XboxStateDescriptionProvider stateDescriptionProvider;

    @Activate
    public XboxHandlerFactory(final @Reference XboxStateDescriptionProvider stateDescriptionProvider) {
        this.stateDescriptionProvider = stateDescriptionProvider;
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return XboxBindingConstants.THING_TYPE_XBOX.equals(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        if (XboxBindingConstants.THING_TYPE_XBOX.equals(thingTypeUID)) {
            return new XboxHandler(thing, stateDescriptionProvider);
        }

        return null;
    }
}
