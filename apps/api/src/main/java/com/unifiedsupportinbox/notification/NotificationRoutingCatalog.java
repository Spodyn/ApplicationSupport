package com.unifiedsupportinbox.notification;

import java.util.List;

/** Provider-neutral read model consumed by durable notification workers. */
public interface NotificationRoutingCatalog {
    List<NotificationRouteView> listEnabledRoutes();
}
