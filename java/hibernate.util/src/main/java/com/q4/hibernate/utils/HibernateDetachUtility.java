/*******************************************************************************
 * The following code has been taken from
 * https://github.com/rupertlssmith/jenerator_utils/blob/master/util/src/main/java/com/thesett/util/hibernate/HibernateDetachUtil.java,
 * and is modified for use.
 * This modified code can be used freely. *
 *******************************************************************************/
package com.q4.hibernate.utils;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import org.hibernate.Hibernate;
import org.hibernate.proxy.HibernateProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HibernateDetachUtil can detach an object from a Hibernate session, in such a way that its uninitialized fields will
 * be set to hold <tt>null</tt> values, and will not cause lazy initialization exceptions when an attempt is made to
 * access them.
 *
 * <p/>
 * An entity may hold relationships to other entities, which may hold further relationships. It is entirely possible
 * that fully expanding all relationships can lead to the entire database being extracted. This utility helps to slice
 * up the results of queries, by trimming off anything that a query has not already fetched.
 *
 */
public class HibernateDetachUtility {
	/** Holds the name of the 'writeReplace' method. */
	public static final String WRITE_REPLACE = "writeReplace";

	/** Holds a string that appears in all javassist class names. */
	public static final String JAVASSIST = "javassist";

	/** The hash code generator used to try and avoid recomputing objects efficiently. */
	static HashCodeGenerator hashCodeGenerator = new SystemHashCodeGenerator();

	/** The maximum recursion depth allowed before termination. */
	private static final int DEPTH_ALLOWED = 50;

	private static final Logger logger = LoggerFactory.getLogger(HibernateDetachUtility.class);

	/** Defines the pattern of field access the objects being detached use. */
	public static enum FieldAccessType {
		/** Direct field access. */
		Field,

		/** Through accessor methods. */
		Accessor
	}

	/** Prevents instantiation of this utility class. */
	private HibernateDetachUtility() {}

	public static void nullOutUninitializedFields(Object value) {
		nullOutUninitializedFields(value, FieldAccessType.Accessor);
	}

	/**
	 * Replaces Hibernate proxies that have been substituted as the values of fields on an object that are waiting to be
	 * lazy loaded, with <tt>null</tt>s. This process is carried out recursively working down the object graph, until a
	 * complete chunk of the object graph has been nulled out, or a maximum depth limit is reached on the recursion.
	 *
	 * @param value The object to null out the uninitialized field of.
	 * @param fieldAccessType The type of field access to use.
	 */
	public static void nullOutUninitializedFields(Object value, FieldAccessType fieldAccessType) {
		Map<Integer, Object> alreadySeen = new HashMap<Integer, Object>();
		Map<Integer, List<Object>> collisionMap = new HashMap<Integer, List<Object>>();

		String packageName = getPackageName(value);

		nullOutUninitializedFields(value, alreadySeen, collisionMap, 0, fieldAccessType, packageName);

		// Help the garbage collector by clearing these.
		alreadySeen.clear();
		collisionMap.clear();
	}

	/**
	 * Provides the package name of the object to detach. If the object to detach is a collection, then the package is
	 * derived from the package of one of its values.
	 *
	 * @param value The object to get the package name of.
	 *
	 * @return The package name of the type to be nulled out.
	 */
	@SuppressWarnings("rawtypes")
	private static String getPackageName(Object value) {
		if (value instanceof Collection) {
			Collection collection = (Collection) value;

			if (collection != null && !collection.isEmpty()) {
				return collection.iterator().next().getClass().getPackage().getName();
			} else {
				return "java.util";
			}
		} else {
			return value.getClass().getPackage().getName();
		}
	}

	/**
	 * Replaces Hibernate proxies that have been substituted as the values of fields on an object that are waiting to be
	 * lazy loaded, with <tt>null</tt>s. This process is carried out recursively working down the object graph, until a
	 * complete chunk of the object graph has been nulled out, or a maximum depth limit is reached on the recursion.
	 *
	 * @param value The object to null out the uninitialized field of.
	 * @param alreadySeen A map of objects already nulled out.
	 * @param collisionMap A map of objects already nulled out that have key collisions.
	 * @param depth The current recursion depth.
	 * @param fieldAccessType The type of field access to use.
	 * @param packageName The name of the package to recursively detach within.
	 */
	private static void nullOutUninitializedFields(Object value, Map<Integer, Object> alreadySeen,
			Map<Integer, List<Object>> collisionMap, int depth, FieldAccessType fieldAccessType, String packageName) {
		// Check that the maximum recursion depth has not been exceeded.
		if (depth > DEPTH_ALLOWED) {
			logger.warn("Maximum depth has been exceeded.");
		}

		// Handle null values and enums trivially.
		if (null == value || value instanceof Enum) {
			logger.debug("null & enum values need not be handled");
			return;
		}

		// Check if the object has already been nulled out and avoid repeating the work if so.
		if (checkIfAlreadySeenAndAdd(value, alreadySeen, collisionMap)) {
			logger.debug("objects have already been handled, so not repeating them");
			return;
		}

		// Null out collection and array types.
		nullOutCollectionsAndArrays(value, alreadySeen, collisionMap, depth, fieldAccessType, packageName);

		// Null out immediate fields, selecting the appropriate strategy by the serialization type.
		nullOutFieldsBySerializationType(value, alreadySeen, collisionMap, depth, fieldAccessType, packageName);
	}

	/**
	 * Nulls out the elements of collection, map and array types.
	 *
	 * @param value The collection or array type to null out the uninitialized fields of the elements of.
	 * @param alreadySeen A map of objects already nulled out.
	 * @param collisionMap A map of objects already nulled out that have key collisions.
	 * @param depth The current recursion depth.
	 * @param fieldAccessType The type of field access to use.
	 * @param packageName The name of the package to recursively detach within.
	 */
	@SuppressWarnings("rawtypes")
	private static void nullOutCollectionsAndArrays(Object value, Map<Integer, Object> alreadySeen,
			Map<Integer, List<Object>> collisionMap, int depth, FieldAccessType fieldAccessType, String packageName) {
		if (value instanceof Object[]) {
			nullOutObjectArray((Object[]) value, alreadySeen, collisionMap, depth, fieldAccessType, packageName);
		} else if (value instanceof List) {
			nullOutList((List) value, alreadySeen, collisionMap, depth, fieldAccessType, packageName);
		} else if (value instanceof Collection) {
			nullOutCollection((Collection) value, alreadySeen, collisionMap, depth, fieldAccessType, packageName);
		} else if (value instanceof Map) {
			nullOutMap((Map) value, alreadySeen, collisionMap, depth, fieldAccessType, packageName);
		}
	}

	/**
	 * Chooses a strategy for nulling out uninitialized fields, depending on the serialization type specified.
	 *
	 * <p/>
	 * For JAXB serialization, provided the access type is METHOD, property access methods are used. If the access
	 * type is FIELD, fields are directly accessed.
	 *
	 * <p/>
	 * For default Serialization, fields are accessed directly.
	 *
	 * @param value The object to null out the uninitialized field of.
	 * @param alreadySeen A map of objects already nulled out.
	 * @param collisionMap A map of objects already nulled out that have key collisions.
	 * @param depth The current recursion depth.
	 * @param fieldAccessType The type of field access to use.
	 * @param packageName The name of the package to recursively detach within.
	 */
	private static void nullOutFieldsBySerializationType(Object value, Map<Integer, Object> alreadySeen,
			Map<Integer, List<Object>> collisionMap, int depth, FieldAccessType fieldAccessType, String packageName) {
		if (fieldAccessType == FieldAccessType.Accessor) {
			XmlAccessorType accessType = value.getClass().getAnnotation(XmlAccessorType.class);

			if (accessType != null && accessType.value() == XmlAccessType.FIELD) {
				nullOutFieldsByFieldAccess(value, alreadySeen, collisionMap, depth, fieldAccessType, packageName);
			} else {
				nullOutFieldsByAccessors(value, alreadySeen, collisionMap, depth, fieldAccessType, packageName);
			}
		} else if (fieldAccessType == FieldAccessType.Field) {
			nullOutFieldsByFieldAccess(value, alreadySeen, collisionMap, depth, fieldAccessType, packageName);
		}
	}

	/**
	 * Checks if an object has already been seen, and consequently no work is required to be performed on it.
	 *
	 * <p/>
	 * Note: The 'alreadySeen' and 'collisionMap' maps may be updated by invoking this, as the object being checked
	 * will be added to the already sees list as a result of calling this.
	 *
	 * @param value The object to check whether it has already been processed.
	 * @param alreadySeen A map of objects already nulled out.
	 * @param collisionMap A map of objects already nulled out that have key collisions.
	 *
	 * @return <tt>true</tt> iff the object has already been processed.
	 */
	private static boolean checkIfAlreadySeenAndAdd(Object value, Map<Integer, Object> alreadySeen,
			Map<Integer, List<Object>> collisionMap) {
		Integer hashCode = hashCodeGenerator.getHashCode(value);
		Object checkedObject = alreadySeen.get(hashCode);

		if (null == checkedObject) {
			alreadySeen.put(hashCode, value);

			return false;
		}

		if (value == checkedObject) {
			return true;
		}

		// In this case the hash code has collided with another objects hash code. Build up a list of all objects
		// with identical hash codes, and check that list for a match.
		List<Object> collisionObjects = collisionMap.get(hashCode);

		if (null == collisionObjects) {
			collisionObjects = new ArrayList<Object>(1);
			collisionMap.put(hashCode, collisionObjects);
		} else {
			for (Object collisionObject : collisionObjects) {
				if (value == collisionObject) {
					// A match was found so the object has already been nulled out.
					logger.debug("Matched object have already been found, so not repeating them");
					return true;
				}
			}
		}

		collisionObjects.add(value);

		return false;
	}

	/**
	 * Nulls out uninitialized fields of the members of an array.
	 *
	 * @param value The array to null out.
	 * @param alreadySeen A map of objects already nulled out.
	 * @param collisionMap A map of objects already nulled out that have key collisions.
	 * @param depth The current recursion depth.
	 * @param fieldAccessType The type of field access to use.
	 * @param packageName The name of the package to recursively detach within.
	 */
	private static void nullOutObjectArray(Object[] value, Map<Integer, Object> alreadySeen,
			Map<Integer, List<Object>> collisionMap, int depth, FieldAccessType fieldAccessType, String packageName) {
		Object[] objArray = value;

		for (int i = 0; i < objArray.length; i++) {
			Object listEntry = objArray[i];
			Object replaceEntry = replaceObject(listEntry);

			if (replaceEntry != null) {
				objArray[i] = replaceEntry;
			}

			nullOutUninitializedFields(objArray[i], alreadySeen, collisionMap, depth + 1, fieldAccessType, packageName);
		}
	}

	/**
	 * Nulls out uninitialized fields of the members of a list.
	 *
	 * @param value The list to null out.
	 * @param alreadySeen A map of objects already nulled out.
	 * @param collisionMap A map of objects already nulled out that have key collisions.
	 * @param depth The current recursion depth.
	 * @param fieldAccessType The type of field access to use.
	 * @param packageName The name of the package to recursively detach within.
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void nullOutList(List value, Map<Integer, Object> alreadySeen, Map<Integer, List<Object>> collisionMap,
			int depth, FieldAccessType fieldAccessType, String packageName) {
		ListIterator i = value.listIterator();

		while (i.hasNext()) {
			Object val = i.next();
			Object replace = replaceObject(val);

			if (replace != null) {
				val = replace;
				i.set(replace);
			}

			nullOutUninitializedFields(val, alreadySeen, collisionMap, depth + 1, fieldAccessType, packageName);
		}
	}

	/**
	 * Nulls out uninitialized fields of the members of a collection.
	 *
	 * @param value The collection to null out.
	 * @param alreadySeen A map of objects already nulled out.
	 * @param collisionMap A map of objects already nulled out that have key collisions.
	 * @param depth The current recursion depth.
	 * @param fieldAccessType The type of field access to use.
	 * @param packageName The name of the package to recursively detach within.
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void nullOutCollection(Collection value, Map<Integer, Object> alreadySeen,
			Map<Integer, List<Object>> collisionMap, int depth, FieldAccessType fieldAccessType, String packageName) {
		Collection collection = value;
		Collection itemsToBeReplaced = new ArrayList();
		Collection replacementItems = new ArrayList();

		for (Object item : collection) {
			Object replacementItem = replaceObject(item);

			if (replacementItem != null) {
				itemsToBeReplaced.add(item);
				replacementItems.add(replacementItem);
				item = replacementItem;
			}

			nullOutUninitializedFields(item, alreadySeen, collisionMap, depth + 1, fieldAccessType, packageName);
		}

		collection.removeAll(itemsToBeReplaced);
		collection.addAll(replacementItems);
	}

	/**
	 * Nulls out uninitialized fields of the members of a map.
	 *
	 * @param value The map to null out.
	 * @param alreadySeen A map of objects already nulled out.
	 * @param collisionMap A map of objects already nulled out that have key collisions.
	 * @param depth The current recursion depth.
	 * @param fieldAccessType The type of field access to use.
	 * @param packageName The name of the package to recursively detach within.
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void nullOutMap(Map value, Map<Integer, Object> alreadySeen, Map<Integer, List<Object>> collisionMap, int depth,
			FieldAccessType fieldAccessType, String packageName) {
		Map originalMap = value;
		Map<Object, Object> replaceMap = new HashMap<Object, Object>();

		for (Iterator i = originalMap.entrySet().iterator(); i.hasNext();) {
			Map.Entry entry = (Map.Entry) i.next();
			Object originalKey = entry.getKey();
			Object originalKeyValue = entry.getValue();

			Object replaceKey = replaceObject(originalKey);
			Object replaceValue = replaceObject(originalKeyValue);

			if (replaceKey != null || replaceValue != null) {
				Object newKey = (replaceKey != null) ? replaceKey : originalKey;
				Object newValue = (replaceValue != null) ? replaceValue : originalKeyValue;
				replaceMap.put(newKey, newValue);
				i.remove();
			}
		}

		originalMap.putAll(replaceMap);

		for (Object iter : originalMap.entrySet()) {
			Map.Entry entry = (Map.Entry) iter;
			Object key = entry.getKey();

			nullOutUninitializedFields(originalMap.get(key), alreadySeen, collisionMap, depth + 1, fieldAccessType, packageName);
			nullOutUninitializedFields(key, alreadySeen, collisionMap, depth + 1, fieldAccessType, packageName);
		}
	}

	/**
	 * Sets the value of all uninitialized fields on an object to <tt>null</tt>. If the field is initialized but is a
	 * collection, a recursive step is made to perform nulling out on the elements of the collection in question.
	 *
	 * <p/>
	 * This method attempts to null out fields using direct field access.
	 *
	 * @param object The object to null out the uninitialized fields of.
	 * @param alreadySeen A map of objects already nulled out.
	 * @param collisionMap A map of objects already nulled out that have key collisions.
	 * @param depth The current recursion depth.
	 * @param fieldAccessType The type of field access to use.
	 * @param packageName The name of the package to recursively detach within.
	 */
	@SuppressWarnings("rawtypes")
	private static void nullOutFieldsByFieldAccess(Object object, Map<Integer, Object> alreadySeen,
			Map<Integer, List<Object>> collisionMap, int depth, FieldAccessType fieldAccessType, String packageName) {
		Class tmpClass = object.getClass();
		List<Field> fieldsToClean = new ArrayList<Field>();

		// Working up the class hierarchy, gather all fields that are not transient or static into a list of fields.
		while (tmpClass != null && tmpClass != Object.class) {
			Field[] declaredFields = tmpClass.getDeclaredFields();

			for (Field declaredField : declaredFields) {
				int modifiers = declaredField.getModifiers();

				if (!((Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers)) || Modifier.isTransient(modifiers))) {
					fieldsToClean.add(declaredField);
				}
			}

			tmpClass = tmpClass.getSuperclass();
		}

		// Null out all of the fields in the list gathered above.
		nullOutFieldsByFieldAccess(object, fieldsToClean, alreadySeen, collisionMap, depth, fieldAccessType, packageName);
	}

	/**
	 * Sets the value of a defined list of uninitialized fields on an object to <tt>null</tt>. If the field is
	 * initialized but is a collection, a recursive step is made to perform nulling out on the elements of the
	 * collection in question.
	 *
	 * <p/>
	 * This method attempts to null out fields using direct field access.
	 *
	 * @param object The object to null out the uninitialized fields of.
	 * @param classFields A list of specific fields to null out.
	 * @param alreadySeen A map of objects already nulled out.
	 * @param collisionMap A map of objects already nulled out that have key collisions.
	 * @param depth The current recursion depth.
	 * @param fieldAccessType The type of field access to use.
	 * @param packageName The name of the package to recursively detach within.
	 */
	private static void nullOutFieldsByFieldAccess(Object object, Iterable<Field> classFields, Map<Integer, Object> alreadySeen,
			Map<Integer, List<Object>> collisionMap, int depth, FieldAccessType fieldAccessType, String packageName) {
		for (Field field : classFields) {
			// Make the field accessible, if it is not already.
			boolean accessModifierFlag = false;

			if (!field.isAccessible()) {
				field.setAccessible(true);
				accessModifierFlag = true;
			}

			// Try to read the value of the field.
			Object fieldValue = null;

			try {
				fieldValue = field.get(object);
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(e);
			}

			// Perform nulling out on field types that may contain uninitialized values.
			nullOutProxiesAndCollections(object, alreadySeen, collisionMap, depth, fieldAccessType, field, fieldValue, packageName);

			if (!checkIfAlreadySeenAndAdd(fieldValue, alreadySeen, collisionMap)) {
				Package fieldPackage = field.getType().getPackage();
				String fieldPackageName = null;

				if (fieldPackage != null) {
					fieldPackageName = fieldPackage.getName();
				}

				if (fieldValue == null) {
				} else if (!packageName.equals(fieldPackageName)) {
				} else if (fieldValue instanceof Collection || fieldValue instanceof Object[] || fieldValue instanceof Map) {
				} else {
					nullOutFieldsBySerializationType(fieldValue, alreadySeen, collisionMap, depth, fieldAccessType, packageName);
				}
			}

			// Restore the accessible state of the field, if it was changed to begin with.
			if (accessModifierFlag) {
				field.setAccessible(false);
			}
		}
	}

	/**
	 * Nulls out an uninitialized field of an object that is a Hibernate proxy or persistent collection, or an ordinary
	 * collection.
	 *
	 * @param object The object to null out the uninitialized fields of.
	 * @param alreadySeen A map of objects already nulled out.
	 * @param collisionMap A map of objects already nulled out that have key collisions.
	 * @param depth The current recursion depth.
	 * @param fieldAccessType The type of field access to use.
	 * @param field The field to null out if not initialized.
	 * @param fieldValue The value that the field currently has.
	 * @param packageName The name of the package to recursively detach within.
	 */
	private static void nullOutProxiesAndCollections(Object object, Map<Integer, Object> alreadySeen,
			Map<Integer, List<Object>> collisionMap, int depth, FieldAccessType fieldAccessType, Field field, Object fieldValue,
			String packageName) {
		if (fieldValue instanceof HibernateProxy) {
			nullOutHibernateProxy(object, alreadySeen, collisionMap, depth, fieldAccessType, field, fieldValue, packageName);
		} else if (fieldValue instanceof org.hibernate.collection.spi.PersistentCollection) {
			nullOutPersistentCollection(object, alreadySeen, collisionMap, depth, fieldAccessType, field, fieldValue, packageName);
		} else if (fieldValue instanceof Collection || fieldValue instanceof Object[] || fieldValue instanceof Map) {
			nullOutUninitializedFields(fieldValue, alreadySeen, collisionMap, depth + 1, fieldAccessType, packageName);
		}
	}

	/**
	 * Nulls out a field that is a Hibernate proxy.
	 *
	 * <p/>
	 * If the field is proxied by Javassist or CGLib in a known way, it may be de-proxied using the 'writeReplace'
	 * method.
	 *
	 * <p/>
	 * If deproxying cannot work, then an instance of the class is created and its id set to the value held by the
	 * Hibernate lazy initialized for the proxy.
	 *
	 * @param object The object to null out the uninitialized fields of.
	 * @param alreadySeen A map of objects already nulled out.
	 * @param collisionMap A map of objects already nulled out that have key collisions.
	 * @param depth The current recursion depth.
	 * @param fieldAccessType The type of field access to use.
	 * @param field The field to null out if not initialized.
	 * @param fieldValue The value that the field currently has.
	 * @param packageName The name of the package to recursively detach within.
	 */
	private static void nullOutHibernateProxy(Object object, Map<Integer, Object> alreadySeen,
			Map<Integer, List<Object>> collisionMap, int depth, FieldAccessType fieldAccessType, Field field, Object fieldValue,
			String packageName) {
		Object replacement = null;
		String assistClassName = fieldValue.getClass().getName();

		if (assistClassName.contains(JAVASSIST) || assistClassName.contains("EnhancerByCGLIB")) {
			replacement = nullOutHibernateProxyByDeproxying(object, alreadySeen, collisionMap, depth, fieldAccessType, field,
					fieldValue, packageName);
		}

		if (replacement == null) {
			nullOutHibernateProxyByObjectConstruction(object, field, (HibernateProxy) fieldValue);
		}
	}

	/**
	 * Nulls out the uninitialized fields of a Hibernate proxied field, if it is possible to access the underlying
	 * object (and recursively null it out).
	 *
	 * @param object The object to null out the uninitialized fields of.
	 * @param alreadySeen A map of objects already nulled out.
	 * @param collisionMap A map of objects already nulled out that have key collisions.
	 * @param depth The current recursion depth.
	 * @param fieldAccessType The type of field access to use.
	 * @param field The field to null out if not initialized.
	 * @param fieldValue The value that the field currently has.
	 * @param packageName
	 *
	 * @return The deproxied object, or <tt>null</tt> if it could not be deproxied.
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static Object nullOutHibernateProxyByDeproxying(Object object, Map<Integer, Object> alreadySeen,
			Map<Integer, List<Object>> collisionMap, int depth, FieldAccessType fieldAccessType, Member field, Object fieldValue,
			String packageName) {
		try {
			Object replacement;
			Class assistClass = fieldValue.getClass();

			Method m = assistClass.getMethod(WRITE_REPLACE);
			replacement = m.invoke(fieldValue);

			if (replacement != null && !replacement.getClass().getName().contains("hibernate")) {
				nullOutUninitializedFields(replacement, alreadySeen, collisionMap, depth + 1, fieldAccessType, packageName);

				setFieldDirect(object, field.getName(), replacement);
			} else {
				replacement = null;
			}

			return replacement;
		} catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Nulls out a Hibernate proxied field, by constructing and instance that matches the fields type, and setting up
	 * its id, from the value held by the Hibernate proxy for lazy loading.
	 *
	 * @param object The object to null out the uninitialized fields of.
	 * @param field The field to null out if not initialized.
	 * @param fieldValue The value that the field currently has.
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void nullOutHibernateProxyByObjectConstruction(Object object, Member field, HibernateProxy fieldValue) {
		try {
			String className = fieldValue.getHibernateLazyInitializer().getEntityName();

			// Check if there is a context class loader that should be used.
			ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();

			Class clazz = contextClassLoader == null ? Class.forName(className) : Class.forName(className, true, contextClassLoader);

			// Try to find a constructor that takes a single integer id argument.
			Constructor idConstructor = null;

			for (Constructor constructor : clazz.getConstructors()) {
				if (constructor.getParameterTypes().length == 1 && constructor.getParameterTypes()[0].equals(Integer.class)) {
					idConstructor = constructor;

					break;
				}
			}

			// Use the id constructor if available, otherwise try to set the id field directly.
			Object replacement;

			if (idConstructor != null) {
				replacement =
						idConstructor.newInstance((Integer) ((HibernateProxy) fieldValue).getHibernateLazyInitializer().getIdentifier());
				setFieldDirect(object, field.getName(), replacement);
			} else {
				Field idField = clazz.getDeclaredField("id");
				Constructor ct = clazz.getDeclaredConstructor();
				ct.setAccessible(true);
				replacement = ct.newInstance();

				if (!idField.isAccessible()) {
					idField.setAccessible(true);
				}

				setFieldDirect(replacement, idField.getName(), fieldValue.getHibernateLazyInitializer().getIdentifier());
				setFieldDirect(object, field.getName(), replacement);
			}
		} catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException | InvocationTargetException
				| IllegalAccessException | InstantiationException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Nulls out a field that is a Hibernate PersistentCollection. If the collection is uninitialized, then it is set to
	 * null. If the collection is initialized, its contents are recursed into to nullify their uninitialized fields as
	 * necessary.
	 *
	 * <p/>
	 * When the PersistentCollection has been initialized, it is copied into an appropriate non-Hibernate collection
	 * type.
	 *
	 * @param object The object to null out the uninitialized fields of.
	 * @param alreadySeen A map of objects already nulled out.
	 * @param collisionMap A map of objects already nulled out that have key collisions.
	 * @param depth The current recursion depth.
	 * @param fieldAccessType The type of field access to use.
	 * @param field The field to null out if not initialized.
	 * @param fieldValue The value that the field currently has.
	 * @param packageName The name of the package to recursively detach within.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void nullOutPersistentCollection(Object object, Map<Integer, Object> alreadySeen,
			Map<Integer, List<Object>> collisionMap, int depth, FieldAccessType fieldAccessType, Member field, Object fieldValue,
			String packageName) {
		if (!((org.hibernate.collection.spi.PersistentCollection) fieldValue).wasInitialized()) {
			nullOutFieldDirect(object, field.getName());
		} else {
			Object replacement = null;

			if (fieldValue instanceof Map) {
				replacement = new HashMap((Map) fieldValue);
				nullOutUninitializedFields(replacement, alreadySeen, collisionMap, depth + 1, fieldAccessType, packageName);
			} else if (fieldValue instanceof List) {
				replacement = new ArrayList((List) fieldValue);
				nullOutUninitializedFields(replacement, alreadySeen, collisionMap, depth + 1, fieldAccessType, packageName);
			} else if (fieldValue instanceof Set) {
				List setAsList = new ArrayList((Set) fieldValue);
				nullOutUninitializedFields(setAsList, alreadySeen, collisionMap, depth + 1, fieldAccessType, packageName);
				replacement = new HashSet(setAsList);
			} else if (fieldValue instanceof Collection) {
				replacement = new ArrayList((Collection) fieldValue);
				nullOutUninitializedFields(replacement, alreadySeen, collisionMap, depth + 1, fieldAccessType, packageName);
			}

			setFieldDirect(object, field.getName(), replacement);
		}
	}

	/**
	 * Provides an instance of the real object, for a hibernate proxied object.
	 *
	 * @param object The object to get the real version of.
	 *
	 * @return A real instance of the object, not a hibernate proxy.
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static Object replaceObject(Object object) {
		Object replacement = null;

		try {
			if (object instanceof HibernateProxy && object.getClass().getName().contains(JAVASSIST)) {
				Class assistClass = object.getClass();

				Method m = assistClass.getMethod(WRITE_REPLACE);
				replacement = m.invoke(object);
			}
		} catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
			throw new IllegalStateException(e);
		}

		return replacement;
	}

	/**
	 * Sets the value of a field on an object to some object value. This bypasses any set method and accesses the field
	 * directly.
	 *
	 * @param object The object to set a field on.
	 * @param fieldName The name of the field.
	 */
	private static void setFieldDirect(Object object, String fieldName, Object newValue) {
		try {
			Field f = object.getClass().getDeclaredField(fieldName);

			if (f != null) {
				f.setAccessible(true);
				f.set(object, newValue);
			}
		} catch (NoSuchFieldException | IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Sets the value of all uninitialized fields on an object to <tt>null</tt>. If the field is initialized but is a
	 * collection, a recursive step is made to perform nulling out on the elements of the collection in question.
	 *
	 * <p/>
	 * This method attempts to null out fields using property accessor methods (set), and falls back to direct field
	 * access only if this is not possible.
	 *
	 * @param object The object to null out the uninitialized fields of.
	 * @param alreadySeen A map of objects already nulled out.
	 * @param collisionMap A map of objects already nulled out that have key collisions.
	 * @param depth The current recursion depth.
	 * @param fieldAccessType The type of field access to use.
	 * @param packageName The name of the package to recursively detach within.
	 */
	private static void nullOutFieldsByAccessors(Object object, Map<Integer, Object> alreadySeen,
			Map<Integer, List<Object>> collisionMap, int depth, FieldAccessType fieldAccessType, String packageName) {
		try {
			BeanInfo bi = Introspector.getBeanInfo(object.getClass(), Object.class);

			PropertyDescriptor[] pds = bi.getPropertyDescriptors();

			for (PropertyDescriptor pd : pds) {
				Object propertyValue = pd.getReadMethod().invoke(object);

				if (!Hibernate.isInitialized(propertyValue)) {
					nullOutField(object, pd);
				} else if (propertyValue instanceof Collection) {
					nullOutUninitializedFields(propertyValue, alreadySeen, collisionMap, depth + 1, fieldAccessType, packageName);
				}
			}
		} catch (IntrospectionException | IllegalAccessException | InvocationTargetException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Sets the value of a field on an object to <tt>null</tt>. This first tries to use a set method to accesses the
	 * field, then falls back on direct access if that is not possible.
	 *
	 * @param object The object to set a field on.
	 * @param pd A reflective descriptor for the field to set to null.
	 */
	private static void nullOutField(Object object, PropertyDescriptor pd) {
		Method writeMethod = pd.getWriteMethod();

		try {
			if (writeMethod != null) {
				pd.getWriteMethod().invoke(object, new Object[] {null});
			} else {
				nullOutFieldDirect(object, pd.getName());
			}
		} catch (IllegalAccessException | InvocationTargetException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Sets the value of a field on an object to <tt>null</tt>. This bypasses any set method and accesses the field
	 * directly.
	 *
	 * @param object The object to set a field on.
	 * @param fieldName The name of the field.
	 */
	private static void nullOutFieldDirect(Object object, String fieldName) {
		try {
			Field f = object.getClass().getDeclaredField(fieldName);

			if (f != null) {
				f.setAccessible(true);
				f.set(object, null);
			}
		} catch (NoSuchFieldException | IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * HashCodeGenerator is a standalone hash code generator, allowing different hash code implementations to be
	 * substituted.
	 */
	static interface HashCodeGenerator {
		/**
		 * Provides a hash code for an object.
		 *
		 * @param value The object to create a hash code for.
		 *
		 * @return A hash code for the object.
		 */
		int getHashCode(Object value);
	}

	/**
	 * Provides hash codes using System.identifyHashCode, which produces unique codes per object instance.
	 */
	static class SystemHashCodeGenerator implements HashCodeGenerator {
		/** {@inheritDoc} */
		public int getHashCode(Object value) {
			return System.identityHashCode(value);
		}
	}
}
