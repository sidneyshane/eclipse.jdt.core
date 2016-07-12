/*******************************************************************************
 * Copyright (c) 2000, 2016 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * This is an implementation of an early-draft specification developed under the Java
 * Community Process (JCP) and is made available for testing and evaluation purposes
 * only. The code is not compatible with any specification of the JCP.
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Stephan Herrmann - Contribution for
 *								Bug 440477 - [null] Infrastructure for feeding external annotations into compilation
 *								Bug 440687 - [compiler][batch][null] improve command line option for external annotations
 *******************************************************************************/
package org.eclipse.jdt.internal.compiler.batch;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException;
import org.eclipse.jdt.internal.compiler.classfmt.ExternalAnnotationProvider;
import org.eclipse.jdt.internal.compiler.env.AccessRuleSet;
import org.eclipse.jdt.internal.compiler.env.IModule;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;
import org.eclipse.jdt.internal.compiler.lookup.ModuleEnvironment;
import org.eclipse.jdt.internal.compiler.util.JRTUtil;
import org.eclipse.jdt.internal.compiler.util.ManifestAnalyzer;
import org.eclipse.jdt.internal.compiler.util.SuffixConstants;
import org.eclipse.jdt.internal.compiler.util.Util;

@SuppressWarnings({"rawtypes", "unchecked"})
public class ClasspathJar extends ClasspathLocation {

protected File file;
protected ZipFile zipFile;
protected ZipFile annotationZipFile;
protected boolean closeZipFileAtEnd;
private static HashMap<String, Set<IModule>> ModulesCache = new HashMap<>();
private Set<String> packageCache;
protected List<String> annotationPaths;
protected boolean isJrt;

public ClasspathJar(File file, boolean closeZipFileAtEnd,
		AccessRuleSet accessRuleSet, String destinationPath, boolean isJrt) {
	super(accessRuleSet, destinationPath);
	this.file = file;
	this.isJrt = isJrt;
	this.closeZipFileAtEnd = closeZipFileAtEnd;
}

public List fetchLinkedJars(FileSystem.ClasspathSectionProblemReporter problemReporter) {
	// expected to be called once only - if multiple calls desired, consider
	// using a cache
	if (this.isJrt) return null;
	InputStream inputStream = null;
	try {
		initialize();
		ArrayList result = new ArrayList();
		ZipEntry manifest = this.zipFile.getEntry("META-INF/MANIFEST.MF"); //$NON-NLS-1$
		if (manifest != null) { // non-null implies regular file
			inputStream = this.zipFile.getInputStream(manifest);
			ManifestAnalyzer analyzer = new ManifestAnalyzer();
			boolean success = analyzer.analyzeManifestContents(inputStream);
			List calledFileNames = analyzer.getCalledFileNames();
			if (problemReporter != null) {
				if (!success || analyzer.getClasspathSectionsCount() == 1 &&  calledFileNames == null) {
					problemReporter.invalidClasspathSection(getPath());
				} else if (analyzer.getClasspathSectionsCount() > 1) {
					problemReporter.multipleClasspathSections(getPath());
				}
			}
			if (calledFileNames != null) {
				Iterator calledFilesIterator = calledFileNames.iterator();
				String directoryPath = getPath();
				int lastSeparator = directoryPath.lastIndexOf(File.separatorChar);
				directoryPath = directoryPath.substring(0, lastSeparator + 1); // potentially empty (see bug 214731)
				while (calledFilesIterator.hasNext()) {
					result.add(new ClasspathJar(new File(directoryPath + (String) calledFilesIterator.next()), this.closeZipFileAtEnd, this.accessRuleSet, this.destinationPath, false));
				}
			}
		}
		return result;
	} catch (IOException | IllegalArgumentException e) {
		// JRE 9 could throw an IAE if the path is incorrect. We are to ignore such
		// linked jars
		return null;
	} finally {
		if (inputStream != null) {
			try {
				inputStream.close();
			} catch (IOException e) {
				// best effort
			}
		}
	}
}
public NameEnvironmentAnswer findClass(String typeName, String qualifiedPackageName, String qualifiedBinaryFileName, IModule mod) {
	return findClass(typeName, qualifiedPackageName, qualifiedBinaryFileName, false, mod);
}
public NameEnvironmentAnswer findClass(String typeName, String qualifiedPackageName, String qualifiedBinaryFileName, boolean asBinaryOnly, IModule mod) {
	if (!isPackage(qualifiedPackageName))
		return null; // most common case

	try {
		ClassFileReader reader = null;
		if (this.isJrt) {
			reader = ClassFileReader.readFromJrt(this.file, qualifiedBinaryFileName, mod);
		} else {
			reader = ClassFileReader.read(this.zipFile, qualifiedBinaryFileName);
		}
		if (reader != null) {
			if (reader.moduleName == null) {
				reader.moduleName = this.module == null ? null : this.module.name();
			}
			if (this.annotationPaths != null) {
				String qualifiedClassName = qualifiedBinaryFileName.substring(0, qualifiedBinaryFileName.length()-SuffixConstants.EXTENSION_CLASS.length()-1);
				for (String annotationPath : this.annotationPaths) {
					try {
						this.annotationZipFile = reader.setExternalAnnotationProvider(annotationPath, qualifiedClassName, this.annotationZipFile, null);
						if (reader.hasAnnotationProvider())
							break;
					} catch (IOException e) {
						// don't let error on annotations fail class reading
					}
				}
			}
			return new NameEnvironmentAnswer(reader, fetchAccessRestriction(qualifiedBinaryFileName));
		}
	} catch(ClassFormatException e) {
		// treat as if class file is missing
	} catch (IOException e) {
		// treat as if class file is missing
	}
	return null;
}
@Override
public boolean hasAnnotationFileFor(String qualifiedTypeName) {
	if (this.isJrt) return false; // TODO: Revisit
	return this.zipFile.getEntry(qualifiedTypeName+ExternalAnnotationProvider.ANNOTATION_FILE_SUFFIX) != null; 
}
public char[][][] findTypeNames(final String qualifiedPackageName, final IModule mod) {
	if (!isPackage(qualifiedPackageName))
		return null; // most common case
	final char[] packageArray = qualifiedPackageName.toCharArray();
	final ArrayList answers = new ArrayList();
	if (this.isJrt) {
		try {
			JRTUtil.walkModuleImage(this.file, new JRTUtil.JrtFileVisitor<java.nio.file.Path>() {

				@Override
				public FileVisitResult visitPackage(java.nio.file.Path dir, java.nio.file.Path modPath, BasicFileAttributes attrs) throws IOException {
					if (qualifiedPackageName.startsWith(dir.toString())) {
						return FileVisitResult.CONTINUE;	
					}
					return FileVisitResult.SKIP_SUBTREE;
				}

				@Override
				public FileVisitResult visitFile(java.nio.file.Path dir, java.nio.file.Path modPath, BasicFileAttributes attrs) throws IOException {
					if (!dir.getParent().toString().equals(qualifiedPackageName)) {
						return FileVisitResult.CONTINUE;
					}
					String fileName = dir.getName(dir.getNameCount() - 1).toString();
					// The path already excludes the folders and all the '/', hence the -1 for last index of '/'
					addTypeName(answers, fileName, -1, packageArray);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitModule(java.nio.file.Path modPath) throws IOException {
					if (mod == ModuleEnvironment.UNNAMED_MODULE)
						return FileVisitResult.CONTINUE;
					if (!CharOperation.equals(mod.name(), modPath.toString().toCharArray())) {
						return FileVisitResult.SKIP_SUBTREE;
					}
					return FileVisitResult.CONTINUE;
				}

			}, JRTUtil.NOTIFY_ALL);
		} catch (IOException e) {
			// Ignore and move on
		}
	} else {
		nextEntry : for (Enumeration e = this.zipFile.entries(); e.hasMoreElements(); ) {
			String fileName = ((ZipEntry) e.nextElement()).getName();

			// add the package name & all of its parent packages
			int last = fileName.lastIndexOf('/');
			if (last > 0) {
				// extract the package name
				String packageName = fileName.substring(0, last);
				if (!qualifiedPackageName.equals(packageName))
					continue nextEntry;
				addTypeName(answers, fileName, last, packageArray);
			}
		}
	}
	int size = answers.size();
	if (size != 0) {
		char[][][] result = new char[size][][];
		answers.toArray(result);
		return result;
	}
	return null;
}

protected void addTypeName(final ArrayList answers, String fileName, int last, char[] packageName) {
	int indexOfDot = fileName.lastIndexOf('.');
	if (indexOfDot != -1) {
		String typeName = fileName.substring(last + 1, indexOfDot);
		answers.add(
			CharOperation.arrayConcat(
				CharOperation.splitOn('/', packageName),
				typeName.toCharArray()));
	}
}
public void initialize() throws IOException {
	if (this.isJrt) {
		loadModules();
	} else if (this.zipFile == null) {
		this.zipFile = new ZipFile(this.file);
	}
}
public void acceptModule(IModule mod) {
	if (this.isJrt) 
		return;
	this.module = mod;
}
public void loadModules() {
	Set<IModule> cache = ModulesCache.get(this.file);

	if (cache == null) {
		try {
			org.eclipse.jdt.internal.compiler.util.JRTUtil.walkModuleImage(this.file,
					new org.eclipse.jdt.internal.compiler.util.JRTUtil.JrtFileVisitor<Path>() {

				@Override
				public FileVisitResult visitPackage(Path dir, Path mod, BasicFileAttributes attrs)
						throws IOException {
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path f, Path mod, BasicFileAttributes attrs)
						throws IOException {
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitModule(Path mod) throws IOException {
					try {
						ClasspathJar.this.acceptModule(JRTUtil.getClassfileContent(ClasspathJar.this.file, MODULE_INFO_CLASS, mod.toString()));
					} catch (ClassFormatException e) {
						e.printStackTrace();
					}
					return FileVisitResult.SKIP_SUBTREE;
				}
			}, JRTUtil.NOTIFY_MODULES);
		} catch (IOException e) {
			// TODO: BETA_JAVA9 Should report better
		}
	}
}
void acceptModule(ClassFileReader reader) {
	if (reader != null) {
		if (this.isJrt) {
			IModule moduleDecl = reader.getModuleDeclaration();
			if (moduleDecl != null) {
				Set<IModule> cache = ModulesCache.get(this.file);
				if (cache == null) {
					ModulesCache.put(new String(moduleDecl.name()), cache = new HashSet<IModule>());
				}
				cache.add(moduleDecl);
			}
		} else {
			this.module = reader.getModuleDeclaration();
		}
	}
	
}
void acceptModule(byte[] content) {
	if (content == null) 
		return;
	ClassFileReader reader = null;
	try {
		reader = new ClassFileReader(content, MODULE_INFO_CLASS.toCharArray(), null);
	} catch (ClassFormatException e) {
		e.printStackTrace();
	}
	if (reader != null) {
		acceptModule(reader);
	}
}
protected void addToPackageCache(String fileName, boolean endsWithSep) {
	int last = endsWithSep ? fileName.length() : fileName.lastIndexOf('/');
	while (last > 0) {
		// extract the package name
		String packageName = fileName.substring(0, last);
		if (this.packageCache.contains(packageName))
			return;
		this.packageCache.add(packageName);
		last = packageName.lastIndexOf('/');
	}
}
public synchronized boolean isPackage(String qualifiedPackageName) {
	if (this.packageCache != null)
		return this.packageCache.contains(qualifiedPackageName);

	this.packageCache = new HashSet<>(41);
	this.packageCache.add(Util.EMPTY_STRING);
	if (this.isJrt) {
		try {
			JRTUtil.walkModuleImage(this.file, new JRTUtil.JrtFileVisitor<java.nio.file.Path>() {

				@Override
				public FileVisitResult visitPackage(java.nio.file.Path dir, java.nio.file.Path mod, BasicFileAttributes attrs) throws IOException {
					addToPackageCache(dir.toString(), true);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(java.nio.file.Path dir, java.nio.file.Path mod, BasicFileAttributes attrs) throws IOException {
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitModule(java.nio.file.Path mod) throws IOException {
					return FileVisitResult.CONTINUE;
				}

			}, JRTUtil.NOTIFY_PACKAGES);
		} catch (IOException e) {
			// Ignore and move on
		}
	} else {
		for (Enumeration e = this.zipFile.entries(); e.hasMoreElements(); ) {
			String fileName = ((ZipEntry) e.nextElement()).getName();
			addToPackageCache(fileName, false);
		}
	}
	return this.packageCache.contains(qualifiedPackageName);
}
public void reset() {
	if (this.closeZipFileAtEnd) {
		if (this.zipFile != null) {
			try {
				this.zipFile.close();
			} catch(IOException e) {
				// ignore
			}
			this.zipFile = null;
		}
		if (this.annotationZipFile != null) {
			try {
				this.annotationZipFile.close();
			} catch(IOException e) {
				// ignore
			}
			this.annotationZipFile = null;
		}
	}
	if (!this.isJrt || this.annotationPaths != null) {
		this.packageCache = null;
		this.annotationPaths = null;
	}
}
public String toString() {
	return "Classpath for jar file " + this.file.getPath(); //$NON-NLS-1$
}
public char[] normalizedPath() {
	if (this.normalizedPath == null) {
		String path2 = this.getPath();
		char[] rawName = path2.toCharArray();
		if (File.separatorChar == '\\') {
			CharOperation.replace(rawName, '\\', '/');
		}
		this.normalizedPath = CharOperation.subarray(rawName, 0, CharOperation.lastIndexOf('.', rawName));
	}
	return this.normalizedPath;
}
public String getPath() {
	if (this.path == null) {
		try {
			this.path = this.file.getCanonicalPath();
		} catch (IOException e) {
			// in case of error, simply return the absolute path
			this.path = this.file.getAbsolutePath();
		}
	}
	return this.path;
}
public int getMode() {
	return BINARY;
}

@Override
public IModule getModule(char[] moduleName) {
	if (this.isJrt) {
		Set<IModule> modules = ModulesCache.get(new String(moduleName));
		if (modules != null) {
			for (IModule mod : modules) {
				if (CharOperation.equals(mod.name(), moduleName))
						return mod;
			}
		}
	} else if (this.module != null && CharOperation.equals(moduleName,  this.module.name())) {
		return this.module;
	}
	return null;
}
@Override
public boolean servesModule(IModule mod) {
	if (!this.isJrt) {
		return super.servesModule(mod);
	}
	if (mod == null) 
		return false;
	if (mod == ModuleEnvironment.UNNAMED_MODULE)
		return true;
	return ModulesCache.containsKey(new String(mod.name()));
}
}
