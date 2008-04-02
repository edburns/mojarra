/*
 * $Id: MockPrincipal.java,v 1.1 2003/03/13 06:06:18 craigmcc Exp $
 */

/*
 * Copyright 2003 Sun Microsystems, Inc. All rights reserved.
 * SUN PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

package javax.faces.mock;


import java.security.Principal;


/**
 * <p>Mock <strong>Principal</strong> object for low-level unit tests.</p>
 */

public class MockPrincipal implements Principal {


    public MockPrincipal() {
        super();
        this.name = "";
        this.roles = new String[0];
    }


    public MockPrincipal(String name) {
        super();
        this.name = name;
        this.roles = new String[0];
    }


    public MockPrincipal(String name, String roles[]) {
        super();
        this.name = name;
        this.roles = roles;
    }


    protected String name = null;


    protected String roles[] = null;


    public String getName() {
        return (this.name);
    }


    public boolean isUserInRole(String role) {
        for (int i = 0; i < roles.length; i++) {
            if (role.equals(roles[i])) {
                return (true);
            }
        }
        return (false);
    }


    public boolean equals(Object o) {
        if (o == null) {
            return (false);
        }
        if (!(o instanceof Principal)) {
            return (false);
        }
        Principal p = (Principal) o;
        if (name == null) {
            return (p.getName() == null);
        } else {
            return (name.equals(p.getName()));
        }
    }


    public int hashCode() {
        if (name == null) {
            return ("".hashCode());
        } else {
            return (name.hashCode());
        }
    }


}
