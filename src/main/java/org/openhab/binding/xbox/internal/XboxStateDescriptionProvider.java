package org.openhab.binding.xbox.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.thing.binding.BaseDynamicStateDescriptionProvider;
import org.openhab.core.thing.i18n.ChannelTypeI18nLocalizationService;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
import org.openhab.core.thing.type.DynamicStateDescriptionProvider;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Stellt dynamische State-Optionen pro Channel bereit. Damit füllt der {@link XboxHandler} die
 * Auswahlliste des {@code launch}-Channels zur Laufzeit aus der Thing-Config {@code appList},
 * sodass das Dropdown automatisch befüllt und ohne Rebuild änderbar ist.
 *
 * @author Jochen
 */
@Component(service = { DynamicStateDescriptionProvider.class, XboxStateDescriptionProvider.class })
@NonNullByDefault
public class XboxStateDescriptionProvider extends BaseDynamicStateDescriptionProvider {

    @Activate
    public XboxStateDescriptionProvider(final @Reference EventPublisher eventPublisher,
            final @Reference ItemChannelLinkRegistry itemChannelLinkRegistry,
            final @Reference ChannelTypeI18nLocalizationService channelTypeI18nLocalizationService) {
        this.eventPublisher = eventPublisher;
        this.itemChannelLinkRegistry = itemChannelLinkRegistry;
        this.channelTypeI18nLocalizationService = channelTypeI18nLocalizationService;
    }
}
