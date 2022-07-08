/*
 * Dog - Core
 * 
 * Copyright (c) 2010-2014 Dario Bonino, Luigi De Russis and Emiliano Castellina
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package it.polito.elite.dog.core.housemodel.simple;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationIntrospector;

import it.polito.elite.dog.core.housemodel.api.EnvironmentModel;
import it.polito.elite.dog.core.housemodel.api.HouseModel;
import it.polito.elite.dog.core.library.jaxb.Building;
import it.polito.elite.dog.core.library.jaxb.BuildingEnvironment;
import it.polito.elite.dog.core.library.jaxb.Configcommand;
import it.polito.elite.dog.core.library.jaxb.Confignotification;
import it.polito.elite.dog.core.library.jaxb.Configparam;
import it.polito.elite.dog.core.library.jaxb.Configstate;
import it.polito.elite.dog.core.library.jaxb.ControlFunctionality;
import it.polito.elite.dog.core.library.jaxb.Controllables;
import it.polito.elite.dog.core.library.jaxb.Device;
import it.polito.elite.dog.core.library.jaxb.DogHomeConfiguration;
import it.polito.elite.dog.core.library.jaxb.Flat;
import it.polito.elite.dog.core.library.jaxb.NotificationFunctionality;
import it.polito.elite.dog.core.library.jaxb.Room;
import it.polito.elite.dog.core.library.jaxb.Storey;
import it.polito.elite.dog.core.library.model.DeviceCostants;
import it.polito.elite.dog.core.library.model.DeviceDescriptor;
import it.polito.elite.dog.core.library.util.LogHelper;

import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ManagedService;
import org.osgi.service.log.LogService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;
import java.util.Vector;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

/**
 * This class implements the HouseModel interface.<br/>
 * It is named SimpleHouseModel since it works without using the ontology.
 * 
 * @author Emiliano Castellina
 * @author <a href="mailto:dario.bonino@polito.it">Dario Bonino</a>
 * @author <a href="mailto:luigi.derussis@polito.it">Luigi De Russis</a>
 * @see <a href="http://elite.polito.it">http://elite.polito.it</a>
 * 
 */
public class SimpleHouseModel implements HouseModel, EnvironmentModel, ManagedService
{
	private static String ENABLE_YAML_MIGRATION =  "enableYamlMigration";
	// bundle context
	private BundleContext context;
	// the device list read from file
	private Hashtable<String, DeviceDescriptor> deviceList;
	// devices group by their type
	private Hashtable<String, HashSet<String>> deviceCategoriesUriList;
	// the SVG house plan
	private String svgPlan;
	// the XML configuration
	private DogHomeConfiguration xmlConfiguration;
	// the JAXB representation of the devices without network-related info
	private List<Controllables> simpleDevicesConfiguration;
	// the logger
	private LogHelper logger;
	// the XML configuration full path
	private String configurationPath;
	// the Jackson mapper
	ObjectMapper mapper;
	// the YAML mapper
	ObjectMapper yamlMapper;
	// yaml migration
	private boolean enableYamlMigration;

	// HouseModel service registration
	private ServiceRegistration<?> srHouseModel;

	// EnvironmentModel service registration
	private ServiceRegistration<?> srEnvironmentModel;

	/**
	 * Default (empty) constructor
	 */
	public SimpleHouseModel()
	{
		// build the Jackson mapper just once as it might be very expensive.

		// create a JacksonXmlModule to customize XML parsing
		JacksonXmlModule xmlModule = new JacksonXmlModule();
		// disable wrapper elements
		xmlModule.setDefaultUseWrapper(false);
		// create a new XML mapper
		this.mapper = new XmlMapper(xmlModule);
		// avoid failure on unknown properties
		mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
		mapper.setSerializationInclusion(Include.NON_EMPTY);
		// pretty printing
		// mapper.enable(SerializationFeature.INDENT_OUTPUT);
		// exploit existing JAXB annotations
		// ad interim solution to be removed when migration to Jackson will
		// complete.
		AnnotationIntrospector introspector = new JaxbAnnotationIntrospector(TypeFactory.defaultInstance());
		mapper.setAnnotationIntrospector(introspector);

		// set up the YAML mapper
		this.yamlMapper = new ObjectMapper(new YAMLFactory());
		this.yamlMapper.findAndRegisterModules();
		this.yamlMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		this.yamlMapper.setSerializationInclusion(Include.NON_NULL);
	}

	/**
	 * Activate this component (after its bind)
	 * 
	 * @param bundleContext
	 *            the bundle context
	 */
	public void activate(BundleContext bundleContext)
	{
		// init
		this.context = bundleContext;
		this.logger = new LogHelper(bundleContext);

		this.deviceCategoriesUriList = new Hashtable<String, HashSet<String>>();
		this.deviceList = new Hashtable<String, DeviceDescriptor>();

		this.svgPlan = "no map loaded";
	}

	/**
	 * Deactivate this component (before its unbind)
	 */
	public void deactivate()
	{
		// unregister services
		this.unRegister();

		// set everything to null
		this.context = null;
		this.logger = null;
		this.deviceCategoriesUriList = null;
		this.deviceList = null;
		this.svgPlan = null;

		this.srHouseModel = null;
		this.srEnvironmentModel = null;
	}

	/**
	 * Called when the configuration of this bundle changes, avoids the bundle
	 * restart.
	 * 
	 * @param context
	 *            The updated bundle context.
	 */
	public void modified(BundleContext context)
	{
		this.context = context;
	}

	/**
	 * Unregister its services from OSGi framework
	 */
	public void unRegister()
	{
		if (this.srHouseModel != null)
		{
			this.srHouseModel.unregister();
		}
		if (this.srEnvironmentModel != null)
		{
			this.srEnvironmentModel.unregister();
		}
	}

	/**
	 * Listen for the configuration and start the XML parsing...
	 */
	@Override
	public void updated(Dictionary<String, ?> properties)
	{
		if (properties != null)
		{
			String devicesFileName = (String) properties.get(DeviceCostants.DEVICES);
			String svgFileName = (String) properties.get(DeviceCostants.SVGPLAN);
			//handle yaml migration
			this.enableYamlMigration = Boolean.valueOf((String)properties.get(SimpleHouseModel.ENABLE_YAML_MIGRATION));

			if (devicesFileName != null && !devicesFileName.isEmpty())
			{
				this.loadConfiguration(devicesFileName, DeviceCostants.DEVICES);
			}

			if (svgFileName != null && !svgFileName.isEmpty())
			{
				this.svgPlan = SimpleHouseModel.fileToString(svgFileName);
			}

			this.registerServices();
		}
	}

	/**
	 * Load and parse the configuration of the current environment.
	 * 
	 * @param configurationFileName
	 *            the file storing the house modelconfiguration
	 * @param type
	 *            the configuration type (it supports {@link DeviceCostants}
	 *            .DEVICES only, at the moment)
	 * @return true if the parsing has been successful or the configuration is
	 *         not empty; false, otherwise.
	 */
	private boolean loadConfiguration(String configurationFileName, String type)
	{
		// profiling
		long t1 = System.currentTimeMillis();

		// only handle device configurations
		if (type.equals(DeviceCostants.DEVICES))
		{
			try
			{
				// store the configuration path as is
				this.configurationPath = configurationFileName;

				// check absolute vs relative
				File configurationFile = new File(this.configurationPath);
				// convert any relative location to a full path assuming that
				// the location is relative to the config folder passes as
				// system property.
				if (!configurationFile.isAbsolute())
				{
					this.configurationPath = System.getProperty("configFolder") + "/" + this.configurationPath;
				}

				// switch parsing depending on file type
				switch (getExtension(this.configurationPath))
				{
					case "xml":
					{
						// --- Jackson-based XML parsing ---
						try
						{
							// create the input factory required to read XML
							// from file
							XMLInputFactory xmlInputFactory = XMLInputFactory.newFactory();
							// create an XML stream reader around the given
							// configuration
							// path
							XMLStreamReader streamReader = xmlInputFactory
									.createXMLStreamReader(new FileInputStream(this.configurationPath));

							this.xmlConfiguration = ((XmlMapper) this.mapper).readValue(streamReader,
									DogHomeConfiguration.class);

							// convert to yaml for allowing update of old releases
							if (this.enableYamlMigration && this.xmlConfiguration != null)
							{
								// save as yaml
								this.yamlMapper.writeValue(new File(this.configurationPath.replace(".xml", ".yaml")),
										this.xmlConfiguration);
							}
						}
						catch (XMLStreamException e)
						{
							this.logger.log(LogService.LOG_ERROR, "Error while reading XML stream " + e);
						}
						break;
					}
					case "yaml":
					{
						this.xmlConfiguration = this.yamlMapper.readValue(new File(this.configurationPath),
								DogHomeConfiguration.class);
					}
				}
			}
			catch (FileNotFoundException e)
			{
				this.logger.log(LogService.LOG_ERROR, "House Model file not found " + e);
			}

			catch (IOException e)
			{
				this.logger.log(LogService.LOG_ERROR, "Error while parsing the House Model " + e);
			}

			if (this.xmlConfiguration != null)
			{

				// create a DeviceDescriptor-based representation of current XML
				// configuration
				this.createDeviceDescriptors();

				// create the JAXB object representing the device list without
				// their
				// network-related properties
				this.createSimpleDevicesRepresentation();
			}

		}

		long t2 = System.currentTimeMillis();
		this.logger.log(LogService.LOG_INFO,
				String.format("Parsing and init time: %.2f s\n", (float) (t2 - t1) / 1000f));

		return this.deviceList.size() > 0;

	}

	/**
	 * Create the devices representation suitable for external usage (e.g., the
	 * REST API), without network related informations.
	 */
	private synchronized void createSimpleDevicesRepresentation()
	{
		// get the full device representation
		this.simpleDevicesConfiguration = this.xmlConfiguration.clone().getControllables();

		for (Device dev : this.simpleDevicesConfiguration.get(0).getDevice())
		{
			this.cleanJaxbDevice(dev);
		}
	}

	/**
	 * Prepare the JAXB Device to contain the proper information for external
	 * applications. It removes all the network-related properties and hides
	 * some redundant arrays for the JSON serialization.
	 * 
	 * @param device
	 *            the "full" JAXB Device to clean
	 */
	private synchronized void cleanJaxbDevice(Device device)
	{
		// store the parameters to be removed from the current device
		Vector<Configparam> paramsToRemove = new Vector<Configparam>();

		// the type value in the XML configuration
		final String network = "network";

		// check, for the "first-level" params, if they are network-related or
		// not
		for (Configparam deviceParam : device.getParam())
		{
			if ((deviceParam.getType() != null) && (!deviceParam.getType().isEmpty())
					&& (deviceParam.getType().equalsIgnoreCase(network)))
			{
				paramsToRemove.add(deviceParam);
			}
		}
		// effectively remove the parameters
		for (Configparam param : paramsToRemove)
		{
			device.getParam().remove(param);
		}
		paramsToRemove.clear();

		// clean the description field
		String description = device.getDescription().trim();
		description = description.replaceAll("\t", "");
		description = description.replaceAll("\n", " ");
		device.setDescription(description);

		// get all the control functionalites...
		List<ControlFunctionality> controlFunctionalities = device.getControlFunctionality();
		for (ControlFunctionality controlFunctionality : controlFunctionalities)
		{
			// get all the commands
			for (Configcommand command : controlFunctionality.getCommands().getCommand())
			{
				for (Configparam param : command.getParam())
				{
					// get all the network-related params
					if ((param.getType() != null) && (!param.getType().isEmpty())
							&& (param.getType().equalsIgnoreCase(network)))
					{
						paramsToRemove.add(param);
					}
				}
				// effectively remove the parameters
				for (Configparam param : paramsToRemove)
				{
					command.getParam().remove(param);
				}
				paramsToRemove.clear();
			}

			// improve non-XML rendering by creating a redundant array
			controlFunctionality.setCommandList(controlFunctionality.getCommands().getCommand());
		}

		// get all the notification functionalities...
		List<NotificationFunctionality> notificationsFunctionalities = device.getNotificationFunctionality();
		for (NotificationFunctionality notificationFunctionality : notificationsFunctionalities)
		{
			// get all the notifications...
			for (Confignotification notification : notificationFunctionality.getNotifications().getNotification())
			{
				for (Configparam param : notification.getParam())
				{
					// get all the network-related params
					if ((param.getType() != null) && (!param.getType().isEmpty())
							&& (param.getType().equalsIgnoreCase(network)))
					{
						paramsToRemove.add(param);
					}
				}
				// effectively remove the parameters
				for (Configparam param : paramsToRemove)
				{
					notification.getParam().remove(param);
				}
				paramsToRemove.clear();
			}

			// improve non-XML rendering by creating a redundant array
			notificationFunctionality
					.setNotificationList(notificationFunctionality.getNotifications().getNotification());
		}

		// improve non-XML rendering by creating a redundant array for states
		if (device.getState() != null)
		{
			for (Configstate status : device.getState())
				if (status.getStatevalues() != null)
				{
					status.setStatevalueList(status.getStatevalues().getStatevalue());
				}
		}

	}

	/**
	 * Create a DeviceDescriptor for each JAXB device obtained from the XML
	 * configuration
	 */
	private void createDeviceDescriptors()
	{

		for (Device dev : this.xmlConfiguration.getControllables().get(0).getDevice())
		{
			DeviceDescriptor currentDescriptor = new DeviceDescriptor(dev);

			// add the Device Descriptor to the device list
			this.deviceList.put(currentDescriptor.getDeviceURI(), currentDescriptor);

			// add the element to the map that stores information grouped by
			// Device Category
			HashSet<String> devicesForDevCategory = this.deviceCategoriesUriList
					.get(currentDescriptor.getDeviceCategory());
			// category does not exist yet...
			if (devicesForDevCategory == null)
			{
				devicesForDevCategory = new HashSet<String>();
				this.deviceCategoriesUriList.put(currentDescriptor.getDeviceCategory(), devicesForDevCategory);
			}
			devicesForDevCategory.add(currentDescriptor.getDeviceURI());
		}

		// final log
		this.logger.log(LogService.LOG_INFO,
				String.format("SimpleHouseModel contains %d device descriptions.", this.deviceList.size()));

	}

	/**
	 * Register the services provided by this bundle
	 */
	private void registerServices()
	{
		if (this.srHouseModel == null)
		{
			// register the offered service
			this.srHouseModel = this.context.registerService(HouseModel.class.getName(), this, null);
		}
		if (this.srEnvironmentModel == null)
		{
			// register the environment model service
			this.srEnvironmentModel = this.context.registerService(EnvironmentModel.class.getName(), this, null);
		}
	}

	/*********************************************************************************
	 * 
	 * HouseModel service - implemented methods
	 * 
	 ********************************************************************************/
	@Override
	public void updateConfiguration(Vector<DeviceDescriptor> updatedDescriptors)
	{
		for (DeviceDescriptor descriptor : updatedDescriptors)
		{
			this.updateDevice(descriptor);
		}

		// recreate the devices list for external usage
		this.createSimpleDevicesRepresentation();

		// write a new XML configuration file on disk
		this.saveConfiguration();
	}

	@Override
	public void updateConfiguration(DeviceDescriptor updatedDescriptor)
	{
		// update the device present in the configuration
		this.updateDevice(updatedDescriptor);

		// recreate the devices list for external usage
		this.createSimpleDevicesRepresentation();

		// write a new XML configuration file on disk
		this.saveConfiguration();
	}

	@Override
	public void addToConfiguration(Vector<DeviceDescriptor> newDescriptors)
	{
		for (DeviceDescriptor descriptor : newDescriptors)
		{
			// insert the device in the configuration
			this.addDevice(descriptor);
		}

		// recreate the devices list for external usage
		this.createSimpleDevicesRepresentation();

		// write a new XML configuration file on disk
		this.saveConfiguration();
	}

	@Override
	public void addToConfiguration(DeviceDescriptor newDescriptor)
	{
		// insert the device in the configuration
		this.addDevice(newDescriptor);

		// recreate the devices list for external usage
		this.createSimpleDevicesRepresentation();

		// write a new XML configuration file on disk
		this.saveConfiguration();
	}

	@Override
	public void removeFromConfiguration(Set<String> deviceURIs)
	{
		for (String device : deviceURIs)
		{
			this.removeDevice(device);
		}

		// recreate the devices list for external usage
		this.createSimpleDevicesRepresentation();

		// write a new XML configuration file on disk
		this.saveConfiguration();
	}

	@Override
	public void removeFromConfiguration(String deviceURI)
	{
		// remove the given device from the configuration
		this.removeDevice(deviceURI);

		// recreate the devices list for external usage
		this.createSimpleDevicesRepresentation();

		// write a new XML configuration file on disk
		this.saveConfiguration();
	}

	/**
	 * Implementation of the updateConfiguration methods, for updating the
	 * devices present in the HouseModel at runtime
	 * 
	 * @param updatedDescriptor
	 *            the {@link DeviceDescriptor} representing the device to update
	 */
	private synchronized void updateDevice(DeviceDescriptor updatedDescriptor)
	{
		// remove the device from the current configuration
		this.removeDevice(updatedDescriptor.getDeviceURI());

		// add the updated device
		this.addDevice(updatedDescriptor);
	}

	/**
	 * Implementation of the addToConfiguration methods, for adding devices to
	 * the HouseModel at runtime
	 * 
	 * @param descriptor
	 *            the {@link DeviceDescriptor} representing the device to add
	 */
	private synchronized void addDevice(DeviceDescriptor descriptor)
	{
		// add the device into the device list
		this.deviceList.put(descriptor.getDeviceURI(), descriptor);

		// add the device into the category list
		HashSet<String> deviceUris = null;
		if (this.deviceCategoriesUriList.containsKey(descriptor.getDeviceCategory()))
		{
			deviceUris = this.deviceCategoriesUriList.get(descriptor.getDeviceCategory());
		}
		else
		{
			deviceUris = new HashSet<String>();
			this.deviceCategoriesUriList.put(descriptor.getDeviceCategory(), deviceUris);
		}
		deviceUris.add(descriptor.getDeviceURI());

		// add the new device into the XML configuration
		if (this.xmlConfiguration != null)
		{
			this.xmlConfiguration.getControllables().get(0).getDevice().add(descriptor.getJaxbDevice());
		}
	}

	/**
	 * Implementation of the removeFromConfiguration methods, for removing
	 * devices from the HouseModel at runtime
	 * 
	 * @param deviceURI
	 *            the URI of the device to remove
	 */
	private synchronized void removeDevice(String deviceURI)
	{
		// remove the device from the device list
		DeviceDescriptor deviceProp = this.deviceList.remove(deviceURI);

		// remove the device from the category list
		if (deviceProp != null)
		{
			String deviceCategory = deviceProp.getDeviceCategory();
			if (deviceCategory != null)
			{
				HashSet<String> devices = this.deviceCategoriesUriList.get(deviceCategory);
				if (devices != null)
				{
					devices.remove(deviceURI);
				}
			}
		}

		// remove the device from the XML configuration
		if (this.xmlConfiguration != null)
		{
			Device removedDevice = null;
			List<Device> devices = this.xmlConfiguration.getControllables().get(0).getDevice();
			boolean found = false;
			for (int i = 0; i < devices.size() && !found; i++)
			{
				Device device = devices.get(i);
				if (device.getId().equals(deviceURI))
				{
					removedDevice = device;
					found = true;
				}

			}
			if (removedDevice != null)
			{
				this.xmlConfiguration.getControllables().get(0).getDevice().remove(removedDevice);
			}
		}
	}

	/**
	 * Save the updated XML configuration onto the disk
	 */
	private synchronized void saveConfiguration()
	{
		if (this.xmlConfiguration != null)
		{
			try
			{
				switch (getExtension(this.configurationPath))
				{

					case "xml":
					{
						// create the XML Output factory needed to correctly
						// serialize
						// the
						// configuration
						XMLOutputFactory xmlOutputFactory = XMLOutputFactory.newFactory();

						try
						{

							// write the output as a string
							StringWriter writer = new StringWriter();
							// wrap the string writer with an XMLStreamWriter
							XMLStreamWriter xmlWriter = xmlOutputFactory.createXMLStreamWriter(writer);
							// write the value
							((XmlMapper) this.mapper).writeValue(xmlWriter, this.xmlConfiguration);

							// pretty printing - needed to improve readability of generated XML file.

							// create an XML transformer
							Transformer t = TransformerFactory.newInstance().newTransformer();
							// ident elements
							t.setOutputProperty(OutputKeys.INDENT, "yes");
							// transform the output string and write it on the
							// configuration
							// file.
							t.transform(new StreamSource(new StringReader(writer.getBuffer().toString())),
									new StreamResult(new File(this.configurationPath)));
							
							if (this.enableYamlMigration) {
								this.yamlMapper.writeValue(new File(this.configurationPath.replace(".xml", ".yaml")), this.xmlConfiguration);
							}
						}
						catch (XMLStreamException | TransformerFactoryConfigurationError
								| TransformerException e)
						{
							this.logger.log(LogService.LOG_ERROR, "Error while writing XML file " + e);
						}
						break;
					}
					case "yaml":
					{

						this.yamlMapper.writeValue(new File(this.configurationPath), this.xmlConfiguration);

						break;
					}
				}
			}
			catch (IOException e)
			{
				this.logger.log(LogService.LOG_ERROR, "Error while writing house model file " + e);
			}
		}
	}

	@Override
	public Vector<DeviceDescriptor> getConfiguration()
	{
		return this.getConfigDevice();
	}

	/**
	 * Implementation of the getConfiguration() method. It inspects the list of
	 * devices to retrieve their information.
	 * 
	 * @return a list of {@link DeviceDescriptor}, containing devices
	 *         information
	 */
	private Vector<DeviceDescriptor> getConfigDevice()
	{
		Hashtable<String, HashSet<String>> condition = null;
		return this.getConfigDevice(condition);
	}

	/**
	 * Implementation of the getConfiguration() method. It inspects the list of
	 * devices to retrieve their information.
	 * 
	 * @param condition
	 *            null for getting all the devices, a list of device URI
	 *            otherwise
	 * @return a list of {@link DeviceDescriptor}, containing devices
	 *         information
	 */
	private Vector<DeviceDescriptor> getConfigDevice(Hashtable<String, HashSet<String>> condition)
	{
		Vector<DeviceDescriptor> devicesProp = new Vector<DeviceDescriptor>();
		HashSet<String> conditionDevices = null;
		HashSet<String> conditionDeviceCategories = null;

		if (condition == null)
		{
			// get all the devices
			conditionDevices = new HashSet<String>(this.deviceList.keySet());
		}
		else
		{
			conditionDevices = condition.get(DeviceCostants.DEVICEURI);
			conditionDeviceCategories = condition.get(DeviceCostants.DEVICE_CATEGORY);
			if (conditionDeviceCategories.size() == 0 && conditionDevices.size() == 0)
			{
				conditionDevices = new HashSet<String>(this.deviceList.keySet());
			}
		}

		if (conditionDevices != null)
		{
			// select only the requested devices
			for (String uri : conditionDevices)
			{
				DeviceDescriptor uriProp = this.deviceList.get(uri);
				if (uriProp != null)
				{
					devicesProp.add(uriProp);
				}

			}
		}
		if (conditionDeviceCategories != null)
		{
			for (String deviceCategory : conditionDeviceCategories)
			{
				HashSet<String> devices = this.deviceCategoriesUriList.get(deviceCategory);
				if (devices != null)
				{
					for (String uri : devices)
					{
						DeviceDescriptor uriProp = this.deviceList.get(uri);
						if (uriProp != null)
						{

							devicesProp.add(uriProp);
						}
					}
				}

			}

		}
		return devicesProp;
	}

	@Override
	public String getSVGPlan()
	{
		return this.svgPlan;
	}

	@Override
	public DogHomeConfiguration getJAXBConfiguration()
	{
		// return the complete JAXB configuration (i.e., rooms, devices,
		// low-level properties, etc.)
		return this.xmlConfiguration;
	}

	@Override
	public List<Controllables> getDevices()
	{
		return this.xmlConfiguration.getControllables();
	}

	@Override
	public List<Controllables> getJAXBDevices()
	{
		List<Controllables> devices = new ArrayList<Controllables>();

		if ((this.xmlConfiguration != null) && (!this.xmlConfiguration.getControllables().isEmpty()))
		{
			devices = this.xmlConfiguration.clone().getControllables();
		}

		// return all the devices with their properties, in their JAXB
		// representation
		return devices;
	}

	@Override
	public List<Controllables> getSimpleDevices()
	{
		return this.simpleDevicesConfiguration;
	}

	@Override
	public List<BuildingEnvironment> getBuildingEnvironment()
	{
		return this.xmlConfiguration.getBuildingEnvironment();
	}

	@Override
	public List<BuildingEnvironment> getJAXBEnvironment()
	{
		List<BuildingEnvironment> building = new ArrayList<BuildingEnvironment>();

		if ((this.xmlConfiguration != null) && (!this.xmlConfiguration.getBuildingEnvironment().isEmpty()))
		{
			building = this.xmlConfiguration.clone().getBuildingEnvironment();
		}

		// return the building structures (flats, rooms, etc.) in their JAXB
		// representation
		return building;
	}

	@Override
	public void updateBuildingConfiguration(Room roomToUpdate, String containerURI)
	{
		if (this.xmlConfiguration != null)
		{
			boolean removed = this.removeRoom(roomToUpdate.getId(), containerURI);

			boolean added = this.addRoom(roomToUpdate, containerURI);

			if ((added) && (removed))
			{
				this.saveConfiguration();
			}
		}
	}

	@Override
	public void updateBuildingConfiguration(Flat flatToUpdate)
	{
		if (this.xmlConfiguration != null)
		{
			boolean removed = this.removeFlat(flatToUpdate.getId());

			boolean added = this.addFlat(flatToUpdate);

			if ((added) && (removed))
			{
				this.saveConfiguration();
			}
		}
	}

	@Override
	public void updateBuildingConfiguration(Flat flatToUpdate, String storeyURI)
	{
		if (this.xmlConfiguration != null)
		{
			boolean removed = this.removeFlat(flatToUpdate.getId());

			boolean added = this.addFlat(flatToUpdate, storeyURI);

			if ((added) && (removed))
			{
				this.saveConfiguration();
			}
		}
	}

	@Override
	public void updateBuildingConfiguration(Storey storeyToUpdate)
	{
		if (this.xmlConfiguration != null)
		{
			boolean removed = this.removeStorey(storeyToUpdate.getId());

			boolean added = this.addStorey(storeyToUpdate);

			if ((added) && (removed))
			{
				this.saveConfiguration();
			}
		}
	}

	@Override
	public void addRoomToBuilding(Room roomToAdd, String containerURI)
	{
		if (this.xmlConfiguration != null && !this.xmlConfiguration.getBuildingEnvironment().isEmpty())
		{
			if (!this.xmlConfiguration.getBuildingEnvironment().get(0).getBuilding().isEmpty())
			{
				boolean added = this.addRoom(roomToAdd, containerURI);

				if (added)
				{
					this.saveConfiguration();
				}
			}
		}
	}

	/**
	 * Implementation of the addRoomToBuilding() method.
	 * 
	 * @param roomToAdd
	 *            the JAXB room object to add
	 * @param containerURI
	 *            the unique name representing where the room is located
	 * @return true if the operation was successful, false otherwise
	 */
	private synchronized boolean addRoom(Room roomToAdd, String containerURI)
	{
		boolean found = false;

		Building building = this.xmlConfiguration.getBuildingEnvironment().get(0).getBuilding().get(0);

		for (Flat flat : building.getFlat())
		{
			if (flat.getId().equals(containerURI) && (!found))
			{
				flat.getRoom().add(roomToAdd);
				found = true;
			}
		}
		for (Storey storey : building.getStorey())
		{
			if (storey.getId().equals(containerURI) && (!found))
			{
				storey.getRoom().add(roomToAdd);
				found = true;
			}
			for (Flat flat : storey.getFlat())
			{
				if (flat.getId().equals(containerURI) && (!found))
				{
					flat.getRoom().add(roomToAdd);
					found = true;
				}
			}
		}

		return found;
	}

	@Override
	public void addFlatToBuilding(Flat flatToAdd)
	{
		if (this.xmlConfiguration != null && !this.xmlConfiguration.getBuildingEnvironment().isEmpty())
		{
			if (!this.xmlConfiguration.getBuildingEnvironment().get(0).getBuilding().isEmpty())
			{
				boolean added = this.addFlat(flatToAdd);

				if (added)
				{
					this.saveConfiguration();
				}
			}
		}
	}

	/**
	 * Implementation of the addFlatToBuilding() method.
	 * 
	 * @param flatToAdd
	 *            the JAXB flat object to add
	 * @return true if the operation was successful, false otherwise
	 */
	private synchronized boolean addFlat(Flat flatToAdd)
	{
		boolean added = this.xmlConfiguration.getBuildingEnvironment().get(0).getBuilding().get(0).getFlat()
				.add(flatToAdd);

		return added;
	}

	@Override
	public void addStoreyToBuilding(Storey storeyToAdd)
	{
		if (this.xmlConfiguration != null && !this.xmlConfiguration.getBuildingEnvironment().isEmpty())
		{
			if (!this.xmlConfiguration.getBuildingEnvironment().get(0).getBuilding().isEmpty())
			{
				boolean added = this.addStorey(storeyToAdd);

				if (added)
				{
					this.saveConfiguration();
				}
			}
		}
	}

	/**
	 * Implementation of the addStoreyToBuilding() method.
	 * 
	 * @param storeyToAdd
	 *            the JAXB storey object to add
	 * @return true if the operation was successful, false otherwise
	 */
	private synchronized boolean addStorey(Storey storeyToAdd)
	{
		boolean added = this.xmlConfiguration.getBuildingEnvironment().get(0).getBuilding().get(0).getStorey()
				.add(storeyToAdd);

		return added;
	}

	@Override
	public void addFlatToStorey(Flat flatToAdd, String storeyURI)
	{
		if (this.xmlConfiguration != null && !this.xmlConfiguration.getBuildingEnvironment().isEmpty())
		{
			if (!this.xmlConfiguration.getBuildingEnvironment().get(0).getBuilding().isEmpty())
			{
				boolean added = this.addFlat(flatToAdd, storeyURI);

				if (added)
				{
					this.saveConfiguration();
				}
			}
		}
	}

	/**
	 * Implementation of the addFlatToStorey() method.
	 * 
	 * @param flatToAdd
	 *            the JAXB flat object to add
	 * @param storeyURI
	 *            the unique name representing in which storey the flat is
	 *            located
	 * @return true if the operation was successful, false otherwise
	 */
	private synchronized boolean addFlat(Flat flatToAdd, String storeyURI)
	{
		boolean added = false;

		for (Storey storey : this.xmlConfiguration.getBuildingEnvironment().get(0).getBuilding().get(0).getStorey())
		{
			if (storey.getId().equals(storeyURI))
			{
				added = storey.getFlat().add(flatToAdd);
			}
		}
		return added;
	}

	@Override
	public void removeRoomFromBuilding(String roomURI, String containerURI)
	{
		if (this.xmlConfiguration != null && !this.xmlConfiguration.getBuildingEnvironment().isEmpty())
		{
			if (!this.xmlConfiguration.getBuildingEnvironment().get(0).getBuilding().isEmpty())
			{
				boolean removed = this.removeRoom(roomURI, containerURI);

				if (removed)
				{
					this.saveConfiguration();
				}
			}
		}
	}

	/**
	 * Implementation of the removeRoomFromBuilding() method.
	 * 
	 * @param roomURI
	 *            the unique name representing the room to remove
	 * @param containerURI
	 *            the unique name representing where the room is located
	 * @return true if the operation was successful, false otherwise
	 */
	private synchronized boolean removeRoom(String roomURI, String containerURI)
	{
		Room removedRoom = null;

		boolean found = false;

		Building building = this.xmlConfiguration.getBuildingEnvironment().get(0).getBuilding().get(0);
		Flat containerFlat = null;

		for (Flat flat : building.getFlat())
		{
			if (flat.getId().equals(containerURI) && (!found))
			{
				for (Room room : flat.getRoom())
				{
					if (room.getId().equals(roomURI) && (!found))
					{
						removedRoom = room;
						containerFlat = flat;
						found = true;
					}
				}
			}
		}
		if ((removedRoom != null) && (containerFlat != null))
		{
			containerFlat.getRoom().remove(removedRoom);
		}
		else
		{
			Storey containerStorey = null;
			for (Storey storey : building.getStorey())
			{
				if (storey.getId().equals(containerURI) && (!found))
				{
					for (Room room : storey.getRoom())
					{
						if (room.getId().equals(roomURI) && (!found))
						{
							containerStorey = storey;
							removedRoom = room;
							found = true;
						}
					}
				}
				for (Flat flat : storey.getFlat())
				{
					if (flat.getId().equals(containerURI) && (!found))
					{
						for (Room room : flat.getRoom())
						{
							if (room.getId().equals(roomURI) && (!found))
							{
								containerFlat = flat;
								removedRoom = room;
								found = true;
							}
						}
					}
				}
			}
			if (removedRoom != null)
			{
				if (containerStorey != null)
				{
					containerStorey.getRoom().remove(removedRoom);
				}
				else if (containerFlat != null)
				{
					containerFlat.getRoom().remove(removedRoom);
				}
			}
		}
		return found;
	}

	@Override
	public void removeFlatFromBuilding(String flatURI)
	{
		if (this.xmlConfiguration != null && !this.xmlConfiguration.getBuildingEnvironment().isEmpty())
		{
			if (!this.xmlConfiguration.getBuildingEnvironment().get(0).getBuilding().isEmpty())
			{
				boolean removed = this.removeFlat(flatURI);

				if (removed)
				{
					this.saveConfiguration();
				}
			}
		}
	}

	/**
	 * Implementation of the removeFlatFromBuilding() method.
	 * 
	 * @param flatURI
	 *            the unique name representing the flat to remove
	 * @return true if the operation was successful, false otherwise
	 */
	private synchronized boolean removeFlat(String flatURI)
	{
		Flat flatToRemove = null;
		boolean found = false;

		for (Flat flat : this.xmlConfiguration.getBuildingEnvironment().get(0).getBuilding().get(0).getFlat())
		{
			if (flat.getId().equals(flatURI) && (!found))
			{
				flatToRemove = flat;
				found = true;
			}
		}
		if (flatToRemove != null)
			this.xmlConfiguration.getBuildingEnvironment().get(0).getBuilding().get(0).getFlat().remove(flatToRemove);
		else
		{
			for (Storey storey : this.xmlConfiguration.getBuildingEnvironment().get(0).getBuilding().get(0).getStorey())
			{
				for (Flat flat : storey.getFlat())
				{
					if (flat.getId().equals(flatURI) && (!found))
					{
						flatToRemove = flat;
						found = true;
					}
				}
				if (flatToRemove != null)
					storey.getFlat().remove(flatToRemove);
			}
		}
		return found;
	}

	@Override
	public void removeStoreyFromBuilding(String storeyURI)
	{
		if (this.xmlConfiguration != null && !this.xmlConfiguration.getBuildingEnvironment().isEmpty())
		{
			if (!this.xmlConfiguration.getBuildingEnvironment().get(0).getBuilding().isEmpty())
			{
				boolean removed = this.removeStorey(storeyURI);

				if (removed)
				{
					this.saveConfiguration();
				}
			}
		}

	}

	/**
	 * Implementation of the removeStoreyFromBuilding() method.
	 * 
	 * @param storeyURI
	 *            the unique name representing the storey to remove
	 * @return true if the operation was successful, false otherwise
	 */
	private synchronized boolean removeStorey(String storeyURI)
	{
		Storey storeyToRemove = null;
		boolean found = false;

		for (Storey storey : this.xmlConfiguration.getBuildingEnvironment().get(0).getBuilding().get(0).getStorey())
		{
			if (storey.getId().equals(storeyURI) && (!found))
			{
				storeyToRemove = storey;
				found = true;
			}
		}
		if (storeyToRemove != null)
		{
			this.xmlConfiguration.getBuildingEnvironment().get(0).getBuilding().get(0).getStorey()
					.remove(storeyToRemove);
		}
		return found;
	}

	/*********************************************************************************
	 * 
	 * Bundle utilities
	 * 
	 ********************************************************************************/

	/**
	 * Read a XML-like file and convert it into a String. Used for reading the
	 * SVG house plan.
	 * 
	 * @param fileName
	 *            the XML-like file to read
	 * @return the file content into a String object
	 */
	private static String fileToString(String fileName)
	{

		FileInputStream inputStream;
		StringBuffer buffer = null;
		try
		{
			inputStream = new FileInputStream(System.getProperty("configFolder") + "/" + fileName);

			BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
			String line = null;
			buffer = new StringBuffer();

			while ((line = reader.readLine()) != null)
			{
				buffer.append(line);

			}
			reader.close();
		}
		catch (IOException e)
		{

			e.printStackTrace();
		}

		return buffer.toString();
	}

	public String getExtension(String filename)
	{
		return filename.substring(filename.lastIndexOf(".") + 1);
	}

}
