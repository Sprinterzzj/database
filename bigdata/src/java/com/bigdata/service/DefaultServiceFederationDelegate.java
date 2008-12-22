/*

Copyright (C) SYSTAP, LLC 2006-2008.  All rights reserved.

Contact:
     SYSTAP, LLC
     4501 Tower Road
     Greensboro, NC 27410
     licenses@bigdata.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

*/
/*
 * Created on Sep 17, 2008
 */

package com.bigdata.service;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.UUID;

import org.apache.log4j.Logger;

/**
 * Basic delegate for services that need to override the service UUID and
 * service interface reported to the {@link ILoadBalancerService}.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class DefaultServiceFederationDelegate<T extends AbstractService>
        implements IFederationDelegate {

    protected final static Logger log = Logger
            .getLogger(DefaultServiceFederationDelegate.class);
    
    final protected T service;
    
    public DefaultServiceFederationDelegate(T service) {
        
        if (service == null)
            throw new IllegalArgumentException();
        
        this.service = service;
        
    }
    
    public String getServiceName() {
        
        return service.getServiceName();
        
    }
    
    public UUID getServiceUUID() {
        
        return service.getServiceUUID();
        
    }
    
    public Class getServiceIface() {
       
        return service.getServiceIface();
        
    }

    /** NOP */
    public void reattachDynamicCounters() {

    }

    /**
     * Returns <code>true</code>
     */
    public boolean isServiceReady() {
        
        return true;

    }
    
    /**
     * NOP
     */
    public void didStart() {
        
    }
    
    /** NOP */
    public void serviceJoin(IService service, UUID serviceUUID) {

    }

    /** NOP */
    public void serviceLeave(UUID serviceUUID) {

    }

    /**
     * Writes the URL of the local httpd service for the {@link DataService}
     * onto a file named <code>httpd.url</code> in the specified
     * directory.
     */
    protected void logHttpdURL(final File dir) {

        final File httpdURLFile = new File(dir, "httpd.url");

        // delete in case old version exists.
        httpdURLFile.delete();

        final String httpdURL = service.getFederation().getHttpdURL();

        if (httpdURL != null) {

            try {

                final Writer w = new BufferedWriter(
                        new FileWriter(httpdURLFile));

                try {

                    w.write(httpdURL);

                } finally {

                    w.close();

                }

            } catch (IOException ex) {

                log.warn("Problem writing httpdURL on file: " + httpdURLFile);

            }

        }

    }

}
