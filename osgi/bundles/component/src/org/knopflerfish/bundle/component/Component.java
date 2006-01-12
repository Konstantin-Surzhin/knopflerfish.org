/*
 * Copyright (c) 2006, KNOPFLERFISH project
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials
 *   provided with the distribution.
 *
 * - Neither the name of the KNOPFLERFISH project nor the names of its
 *   contributors may be used to endorse or promote products derived
 *   from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.knopflerfish.bundle.component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Dictionary;
import java.util.Enumeration;


import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.ComponentInstance;

public abstract class Component {

  protected Config config; 
  private boolean enabled;
  private boolean active;
  private Dictionary properties;
  private Object instance;
  private ComponentContext context;

  public Component(Config config, Dictionary overriddenProps) {

    this.config = config;
    properties = config.getProperties();

    instance = null;
    context = null;

    if (overriddenProps == null) {

      for (Enumeration e = overriddenProps.keys(); 
           e.hasMoreElements(); ) {
        Object key = e.nextElement();
        properties.put(key, overriddenProps.get(key));
      }
    }
    
  }

  /** enables this component */
  public void enable() { 
    enabled = true; 
    /* Think about: if it was earlier satisfied then
       we might have to activate this component */
  }
    
  /** disables the component */
  public void disable() {
    enabled = false;
  }
  
  /** checks whether a component is enabled */
  public boolean isEnabled() {
    return enabled;
  }

  /** activates a component */
  public void activate() {
    // this method is described on page 297 r4
    if (!isEnabled() || config.isSatisfied() || isActivated()) 
      return ;

    // 1. load class

    Class klass = null;
    try {

      Bundle bundle = config.getBundle();       
      ClassLoader loader = Backdoor.getClassLoader(bundle); 
      loader.loadClass(config.getImplementation());

    } catch (ClassNotFoundException e) {
      if (Activator.log.doError())
        Activator.log.error("Could not find class " + 
                            config.getImplementation());

    }

    ComponentInstance cInstance = null;
    
    try {
      // 2. create ComponentContext and ComponentInstance
      instance = klass.newInstance();
      cInstance = new ComponentInstanceImpl(this, instance);
      context = new ComponentContextImpl(this, cInstance);
            
      
    }  catch (IllegalAccessException e) {
      if (Activator.log.doError())
        Activator.log.error("Could not access constructor of class " + 
                            config.getImplementation());

    } catch (InstantiationException e) {
      if (Activator.log.doError())
        Activator.log.error("Could not create instance of " + 
                            config.getImplementation() + 
                            " isn't a proper class.");
      
    } catch (ExceptionInInitializerError e) {
      if (Activator.log.doError())
        Activator.log.error("Constructor for " + 
                            config.getImplementation() + 
                            " threw exception.", e);

    } catch (SecurityException e) {
      if (Activator.log.doError())
        Activator.log.error("Did not have permissions to create an instance of " + 
                            config.getImplementation(), e);
    }
    
    // 3. Bind the services. This should be sent to all the references.
    try {
      config.bindReferences(instance);
      
    } catch (Exception e) {
      // log errors.
    }

    try {

      Method method = klass.getMethod("activate", 
				      new Class[]{ ComponentContext.class });
      method.invoke(instance, new Object[]{ context });

    } catch (NoSuchMethodException e) {
      // this instance does not have an activate method, (which is ok)
    } catch (IllegalAccessException e) {
      Activator.log.error("Declarative Services could not invoke \"deactivate\""  + 
			  " method in component \""+ config.getName() + 
			  "\". Got exception", e);
  
    } catch (InvocationTargetException e) {
      // the method threw an exception.
      Activator.log.error("Declarative Services got exception when invoking " + 
			  "\"activate\" in component " + config.getName(), e); 
      
      // if this happens the component should not be activatated
      config.unbindReferences(instance);
      instance = null;
      context = null;
    }

    active = true;
  }

  /** deactivates a component */
  public void deactivate() {
    // this method is described on page 432 r4
    
    if (!isActivated())
      return ;
    
    try {
      Class klass = instance.getClass();
      Method method = klass.getMethod("deactivate", 
				      new Class[]{ ComponentContext.class });
      
      method.invoke(instance, new Object[]{ context });

    } catch (NoSuchMethodException e) {
      // this instance does not have an deactivate method, (which is ok)
    } catch (IllegalAccessException e) {
      Activator.log.error("Declarative Services could not invoke \"deactivate\"" + 
			  " method in component \""+ config.getName() + 
			  "\". Got exception", e);
  
    } catch (InvocationTargetException e) {
      // the method threw an exception.
      Activator.log.error("Declarative Services got exception when invoking " + 
			  "\"deactivate\" in component " + config.getName(), e); 
    }
    
    config.unbindReferences(instance);
    instance = null;
    context = null;
    active = false;
  }
  
  public boolean isActivated() {
    return active;
  }

  /** 
      this method is called whenever this components configuration
      becomes satisfied.
   */
  public abstract void satisfied(boolean isSatisfied);


  // might want to refactor these.. maybe not.
  private class ComponentInstanceImpl implements ComponentInstance {
    
    private Component component;
    private Object instance;

    ComponentInstanceImpl(Component component, Object instance) {
      this.component = component;
      this.instance = instance;
    }
    
    public void dispose() {
      component.deactivate();
    }

    public Object getInstance() {
      if (component.isActivated())
        return instance;
      
      return null;
    }
    
  }

  private class ComponentContextImpl implements ComponentContext {
    private Component component;
    private ComponentInstance instance;

    ComponentContextImpl(Component component, ComponentInstance instance) {
      this.component = component;
    }

    public Dictionary getProperties() {
      return properties;
    }

    public Object locateService(String name) {
       throw new RuntimeException("not yet implemented");      
    }

    public Object[] locateServices(String name) {
      throw new RuntimeException("not yet implemented");
    }

    public BundleContext getBundleContext() {
      throw new RuntimeException("not yet implemented");
    }

    public Bundle getUsingBundle() {
      throw new RuntimeException("not yet implemented");
    }

    public ComponentInstance getComponentInstance() {
      return instance;
    }

    public void enableComponent(String name) {
      throw new RuntimeException("not yet implemented");
    }
    
    public void disableComponent(String name) {
      throw new RuntimeException("not yet implemented");
    }

    public ServiceReference getServiceReference() {
      throw new RuntimeException("not yet implemented");
    }
  }
}
