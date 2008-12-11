/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.faces.application.view;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.faces.application.StateManager;
import javax.faces.component.UIComponent;
import javax.faces.component.UIViewRoot;
import javax.faces.component.visit.VisitResult;
import javax.faces.context.FacesContext;
import javax.faces.render.ResponseStateManager;

import com.sun.faces.io.FastStringWriter;
import com.sun.faces.renderkit.RenderKitUtils;
import com.sun.faces.util.DebugUtil;
import com.sun.faces.util.FacesLogger;
import com.sun.faces.util.MessageUtils;
import java.util.HashMap;
import javax.faces.component.visit.VisitCallback;
import javax.faces.component.visit.VisitContext;
import javax.faces.webapp.pdl.StateManagementStrategy;

/**
 * <p>
 * A <code>StateManager</code> implementation to meet the requirements
 * of the specification.
 * </p>
 *
 * <p>
 * For those who had compile dependencies on this class, we're sorry for any
 * inconvenience, but this had to be re-worked as the version you depended on
 * was incorrectly implemented.  
 * </p>
 */
public class StateManagementStrategyImpl extends StateManagementStrategy {

    private static final Logger LOGGER = FacesLogger.APPLICATION.getLogger();


    // ------------------------------------------------------------ Constructors


    /**
     * Create a new <code>StateManagerImpl</code> instance.
     */
    public StateManagementStrategyImpl() {

    }


    // ----------------------------------------------- Methods from StateManager


    /**
     * @see {@link javax.faces.application.StateManager#saveView(javax.faces.context.FacesContext))
     */
    @Override
    public Object saveView(FacesContext context) {

        if (context == null) {
            return null;
        }

        // irrespective of method to save the tree, if the root is transient
        // no state information needs to  be persisted.
        UIViewRoot viewRoot = context.getViewRoot();
        if (viewRoot.isTransient()) {
            return null;
        }

        // honor the requirement to check for id uniqueness
        checkIdUniqueness(context,
                          viewRoot,
                          new HashSet<String>(viewRoot.getChildCount() << 1));
        final Map<String,Object> stateMap = new HashMap<String,Object>();
        
        VisitContext visitContext = VisitContext.createVisitContext(context);
        
        DebugUtil.printTree(viewRoot, LOGGER, Level.INFO);
        
        viewRoot.visitTree(visitContext, new VisitCallback() {

            public VisitResult visit(VisitContext context, UIComponent target) {
                VisitResult result = VisitResult.ACCEPT;
                Object stateObj = target.saveState(context.getFacesContext());
                if (null != stateObj) {
                    stateMap.put(target.getClientId(context.getFacesContext()),
                            stateObj);
                }
                
                return result;
            }
            
        });

        return stateMap;

    }


    /**
     * @see {@link StateManager#restoreView(javax.faces.context.FacesContext, String, String)}
     */
    public UIViewRoot restoreView(FacesContext context,
                                  String viewId,
                                  String renderKitId) {

        ResponseStateManager rsm =
              RenderKitUtils.getResponseStateManager(context, renderKitId);
        
        // Build the tree to initial state
        UIViewRoot viewRoot = context.getApplication().getViewHandler().createView(context, viewId);

        DebugUtil.printTree(viewRoot, LOGGER, Level.INFO);
        
        final Map<String, Object> state = (Map<String,Object>) rsm.getState(context, viewId);

        if (null != state) {
            // We need to clone the tree, otherwise we run the risk
            // of being left in a state where the restored
            // UIComponent instances are in the session instead
            // of the TreeNode instances.  This is a problem
            // for servers that persist session data since
            // UIComponent instances are not serializable.
            VisitContext visitContext = VisitContext.createVisitContext(context);
            viewRoot.visitTree(visitContext, new VisitCallback() {

                public VisitResult visit(VisitContext context, UIComponent target) {
                    VisitResult result = VisitResult.ACCEPT;
                    Object stateObj = state.get(target.getClientId(context.getFacesContext()));
                    if (null != stateObj) {
                        target.restoreState(context.getFacesContext(),
                                stateObj);
                    }

                    return result;
            }
            
        });
            
        } else {
            viewRoot = null;
        }

        return viewRoot;

    }


    // ------------------------------------------------------- Protected Methods


    protected void checkIdUniqueness(FacesContext context,
                                     UIComponent component,
                                     Set<String> componentIds)
          throws IllegalStateException {

        // deal with children/facets that are marked transient.
        for (Iterator<UIComponent> kids = component.getFacetsAndChildren();
             kids.hasNext();) {

            UIComponent kid = kids.next();
            // check for id uniqueness
            String id = kid.getClientId(context);
            if (componentIds.add(id)) {
                checkIdUniqueness(context, kid, componentIds);
            } else {
                if (LOGGER.isLoggable(Level.SEVERE)) {
                    LOGGER.log(Level.SEVERE,
                               "jsf.duplicate_component_id_error",
                               id);
                }
                FastStringWriter writer = new FastStringWriter(128);
                DebugUtil.simplePrintTree(context.getViewRoot(), id, writer);
                String message = MessageUtils.getExceptionMessageString(
                            MessageUtils.DUPLICATE_COMPONENT_ID_ERROR_ID, id)
                      + '\n'
                      + writer.toString();
                throw new IllegalStateException(message);
            }
        }

    }

} 
