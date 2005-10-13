 /*******************************************************************************
 * Copyright (c) 2005 BEA Systems, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    mkaufman@bea.com - initial API and implementation
 *******************************************************************************/


package org.eclipse.jdt.apt.core.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.apt.core.AptPlugin;
import org.eclipse.jdt.apt.core.env.Phase;
import org.eclipse.jdt.apt.core.internal.APTDispatch.APTResult;
import org.eclipse.jdt.apt.core.internal.env.EclipseRoundCompleteEvent;
import org.eclipse.jdt.apt.core.internal.env.ProcessorEnvImpl;
import org.eclipse.jdt.apt.core.internal.generatedfile.GeneratedFileManager;
import org.eclipse.jdt.apt.core.util.ScannerUtil;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.CompilationUnit;

import com.sun.mirror.apt.AnnotationProcessor;
import com.sun.mirror.apt.AnnotationProcessorFactory;
import com.sun.mirror.apt.AnnotationProcessorListener;
import com.sun.mirror.apt.RoundCompleteListener;
import com.sun.mirror.declaration.AnnotationTypeDeclaration;

/*package*/ class APTDispatchRunnable implements IWorkspaceRunnable
{
	public static final APTResult EMPTY_APT_RESULT = new APTResult();
	public static final IFile[] NO_FILES = new IFile[0];
	// TODO: until we get the UI in place, we will just hard code a few known factories.
	private static final Set<String> BATCH_FACTORY_PREFIX;
	// The files that requires processing.
	private IFile[] _allFilesRequireProcessing = null;
	// the original list of files before any filtering.
	private final IFile[] _originalFiles;
	private final ICompilationUnit _compilationUnit;
	private final IJavaProject _javaProject;
	private final List<AnnotationProcessorFactory> _factories;
	private  APTResult _result;
	private final boolean _isFullBuild;
	
	static{
		BATCH_FACTORY_PREFIX = new HashSet<String>(2);
		BATCH_FACTORY_PREFIX.add("com.sun.istack.ws"); //$NON-NLS-1$
		BATCH_FACTORY_PREFIX.add("com.sun.tools.ws.processor.modeler.annotation"); //$NON-NLS-1$
	}
	
	/*package*/ APTDispatchRunnable( 
			IFile[] files, 
			IJavaProject javaProject, 
			List<AnnotationProcessorFactory> factories,
			boolean isFullBuild)
	{
		assert files != null : "missing files"; //$NON-NLS-1$
		_compilationUnit = null;
		_allFilesRequireProcessing = getFilesToProcess(files);
		_originalFiles = files;
		_javaProject = javaProject;
		_factories = factories;
		_isFullBuild = isFullBuild;
	}	
	/*package*/ APTDispatchRunnable( ICompilationUnit cu, IJavaProject javaProject, List<AnnotationProcessorFactory> factories)
	{
		_compilationUnit = cu;
		final IFile file = (IFile)cu.getResource();
		_allFilesRequireProcessing = ScannerUtil.hasAnnotationInstance(file) ?
				new IFile[]{file} : NO_FILES;
		_originalFiles = new IFile[]{file};
		_javaProject = javaProject;
		_factories = factories;
		_isFullBuild = false;
	}
	
	public APTResult getResult() { return _result; }
	
	private static IFile[] getFilesToProcess(final IFile[] orig)
	{			
		int numFiles = orig.length;
		if( numFiles == 0 )
			return NO_FILES;
		int count = 0;
		boolean[] needProcess = new boolean[numFiles];
		for( int i=0; i<numFiles; i++ ){
			if( ScannerUtil.hasAnnotationInstance(orig[i]) ){
				count ++;
				needProcess[i] = true;
			}
			else{
				needProcess[i] = false;
			}
		}
		if( count == 0 )
			return NO_FILES;
		
		IFile[] filesToProcess = new IFile[count];
		int index = 0;
		for( int i=0; i<numFiles; i++ ){
			if( needProcess[i] )
				filesToProcess[index++] = orig[i];
		}
		return filesToProcess;
	}
	
	/**
	 * Determine whether there are files to be processed. 
	 * This call also make sure that the list of files contains exactly the list of 
	 * files to be processed. The size of the list is the number of files to 
	 * be processed.
	 * @return
	 */
	private boolean shouldProcess()
	{
		if(_factories == null | _factories.size() == 0 )
			return false;
		return _allFilesRequireProcessing.length > 0;
	}
	
	public void run(IProgressMonitor monitor) 
	{	
		//
		//  bail-out early if there aren't factories, or if there aren't any annotation instances
		// 
		if ( !shouldProcess() )
		{
			// tracing
			if ( AptPlugin.DEBUG ) 
			{			
				String msg;
				if ( _factories == null || _factories.size() == 0 )
					msg = "no AnnotationProcessoryFactory instances registered."; //$NON-NLS-1$
				else
					msg = "no annotation instances in file."; //$NON-NLS-1$
				trace( "run():  leaving early because there are " + msg, //$NON-NLS-1$
					   null);
			}

			Set<IFile> allDeletedFiles = new HashSet<IFile>();
			if( !_isFullBuild ){
				for( int i=0, len = _originalFiles.length; i<len; i++ ){
					IFile f = _originalFiles[i];
					final Set<IFile> deletedFiles = 
						cleanupAllGeneratedFilesForParent( f, _compilationUnit, null );
					if( deletedFiles != null )
						allDeletedFiles.addAll(deletedFiles);
				}
			}
			
			if ( allDeletedFiles.size() == 0 )
				_result =  EMPTY_APT_RESULT;
			else
				_result = new APTResult( Collections.<IFile>emptySet(), 
										 allDeletedFiles, 
										 Collections.<IFile, Set<String>>emptyMap(),
										 Collections.<IFile, List<IProblem>>emptyMap(), 
										 false, false );
		}
		else
		{
			ProcessorEnvImpl processorEnv;
			
			if ( _compilationUnit != null )
			{
				processorEnv = ProcessorEnvImpl
					.newProcessorEnvironmentForReconcile(_compilationUnit, _javaProject);
			}
			else
			{
				processorEnv = ProcessorEnvImpl
					.newProcessorEnvironmentForBuild( _allFilesRequireProcessing, _javaProject);
			}
			_result = runAPT(_factories, processorEnv);
		}
	}
	
	/**
	 * @param factories
	 * @return <code>true</code> iff there are factories that can only be run in batch mode.
	 */
	public static boolean hasBatchFactory(List<AnnotationProcessorFactory> factories)
	{
		
		for( AnnotationProcessorFactory factory : factories ){
			if( isBatchFactory(factory) )
				return true;
		}
		return false;
		
	}
	
	/**
	 * @param factory
	 * @return <code>true</code> iff the given factory can only be run in batch mode.
	 */
	public static boolean isBatchFactory(AnnotationProcessorFactory factory)
	{
		// TODO: (theodora)handle the switch between file-based and batch processor properly
		//       Waiting on UI support. Default is file base.	
		final String factoryName = factory.getClass().getName();
		for(String prefix : BATCH_FACTORY_PREFIX ){
			if( factoryName.startsWith(prefix) )
				return true;
		}
		return false;
	}
	
	private static void addFileWithMissingTypeError(
			final IFile file, 
			final List<IFile> filesWithMissingType,
			final List<char[]> sourceForFilesWithMissingType,
			final ProcessorEnvImpl processorEnv)
	{
		if( processorEnv.getPhase() != Phase.BUILD )
			return;
		final CompilationUnit unit = processorEnv.getAstCompilationUnit(file);
		assert unit != null : "cannot locate compilation unit for " + file.getName(); //$NON-NLS-1$
		final IProblem[] problems = unit.getProblems();
		if(problems == null)
			return;
		for( IProblem problem : problems ){
			if( problem.getID() == IProblem.UndefinedType ){						
				filesWithMissingType.add(file);
				final char[] src = processorEnv.getSourceForFile(file);
				assert src != null : "cannot locate source for file " + file.getName(); //$NON-NLS-1$
				sourceForFilesWithMissingType.add(src);
			}
		}	
	}
	
	private static void addAllFilesWithMissingTypeError(
			final List<IFile> filesWithMissingType,
			final List<char[]> sourceForFilesWithMissingType,
			final ProcessorEnvImpl processorEnv)
	{
		if( processorEnv.getPhase() != Phase.BUILD )
			return;
		final IFile[] files = processorEnv.getFiles();
		for( int i=0, len=files.length; i<len; i++ ){
			final IFile file = files[i];
			addFileWithMissingTypeError(file, filesWithMissingType, sourceForFilesWithMissingType, processorEnv);
		}
	}
	
	/**
	 * Batch processor should only be invoked during a clean build.
	 * @param factories
	 * @param processorEnv
	 * @return <code>true</code> iff batch processors should be dispatched.
	 * Return <code>false</code> otherwise. Return <code>false</code> if
	 * there are no batch processors.
	 */
	private boolean shouldDispatchToBatchProcessor(final List<AnnotationProcessorFactory> factories,
										 		   final ProcessorEnvImpl processorEnv )
	{	
		return ( _isFullBuild && processorEnv.getPhase() == Phase.BUILD && hasBatchFactory(factories) );
	}
	
	private void runAPTInFileBasedMode(
			final List<AnnotationProcessorFactory> factories,
			final ProcessorEnvImpl processorEnv,
			final List<IFile> filesWithMissingType,
			final List<char[]> sourceForFilesWithMissingType)
	{
		final IFile[] files = processorEnv.getFiles();
		for (IFile curFile : files ) {
			processorEnv.setFileProcessing(curFile);
			Map<String, AnnotationTypeDeclaration> annotationDecls = processorEnv.getAnnotationTypesInFile();
			for (int factoryIndex = 0, numFactories = factories.size(); factoryIndex < numFactories; factoryIndex++) {
				final AnnotationProcessorFactory factory = factories.get(factoryIndex);
				if( isBatchFactory(factory) ) continue;
				Set<AnnotationTypeDeclaration> factoryDecls = getFactorySupportedAnnotations(factory, annotationDecls);
				if( factoryDecls != null ){
					if(factoryDecls.size() == 0 ){
						factoryDecls = new HashSet(annotationDecls.values());
						annotationDecls.clear();
					}
				}
				if (factoryDecls != null && factoryDecls.size() > 0) {
					final AnnotationProcessor processor = factory
							.getProcessorFor(factoryDecls, processorEnv);
					if (processor != null)
					{
						if ( AptPlugin.DEBUG ) {
							trace( "runAPT: invoking file-based processor " + processor.getClass().getName() + " on " + curFile, //$NON-NLS-1$ //$NON-NLS-2$ 
									processorEnv); 
							
						}
	                    processorEnv.setLatestProcessor(processor);
						processor.process();
						addFileWithMissingTypeError(curFile, filesWithMissingType, sourceForFilesWithMissingType, processorEnv);
					}
				}
	
				if (annotationDecls.isEmpty())
					break;
			}
			
			if( ! annotationDecls.isEmpty() )
				; // TODO: (theodora) log unclaimed annotations.
		}	
	}
	
	/**
	 * mixed mode - allow batch processor to be run as well as filed based ones.
	 * @param factories
	 * @param processorEnv
	 */
	private void runAPTInMixedMode(
			final List<AnnotationProcessorFactory> factories,
			final ProcessorEnvImpl processorEnv,
			final List<IFile> filesWithMissingType,
			final List<char[]> sourceForFilesWithMissingType)
	{
		final IFile[] files = processorEnv.getFiles();
		final Map<IFile, Set<AnnotationTypeDeclaration>> file2AnnotationDecls = 
			new HashMap<IFile, Set<AnnotationTypeDeclaration>>(files.length * 4/3 + 1);
		final Map<String, AnnotationTypeDeclaration> annotationDecls = 
			processorEnv.getAllAnnotationTypes(file2AnnotationDecls);	
		addAllFilesWithMissingTypeError(filesWithMissingType, sourceForFilesWithMissingType, processorEnv);
		
		if (annotationDecls.isEmpty())
		{
			if ( AptPlugin.DEBUG ) 
				trace( "runAPT:  leaving early because annotationDecls is empty", //$NON-NLS-1$
					   processorEnv); 
			return;
		}
		
		// file based processing factory to the set of annotations that it 'claims'
		final Map<AnnotationProcessorFactory, Set<AnnotationTypeDeclaration>> fileFactory2Annos =
			new HashMap<AnnotationProcessorFactory, Set<AnnotationTypeDeclaration>>( factories.size() * 4/3 + 1 );
		
		// batch processing factory to the set of annotations that it 'claims'
		final Map<AnnotationProcessorFactory, Set<AnnotationTypeDeclaration>> batchFactory2Annos =
			new HashMap<AnnotationProcessorFactory, Set<AnnotationTypeDeclaration>>( factories.size() * 4/3 + 1 );		
		
		for( int i=0, size=factories.size(); i<size; i++ ){
			final AnnotationProcessorFactory factory = factories.get(i);
			Set<AnnotationTypeDeclaration> annotationTypes = getFactorySupportedAnnotations(factory, annotationDecls);
			if( annotationTypes != null ){
				
				boolean batch = isBatchFactory(factory);
				Map<AnnotationProcessorFactory, Set<AnnotationTypeDeclaration> > factory2Annos = 
					batch ? batchFactory2Annos : fileFactory2Annos;
				if( annotationTypes.size() == 0 ){
					// this factory is claiming all (remaining) annotations. 
					annotationTypes = new HashSet<AnnotationTypeDeclaration>(annotationDecls.values());
					factory2Annos.put(factory, annotationTypes);
					annotationDecls.clear();
					break;
				}
				else{
					factory2Annos.put(factory, annotationTypes);
				}
			}
			if( annotationDecls.isEmpty() )
				break;
		}
		
		if( ! annotationDecls.isEmpty() )
			; // TODO: (theodora) log unclaimed annotations.
		
		// Dispatch to the batch process factories first.
		// Batch processors only get executed on a full/clean build.
		if( !batchFactory2Annos.isEmpty() ){
			processorEnv.setBatchProcessing();
			// Once we figure out which factory claims what annotation,
			// the order of the factory doesn't matter.
			// But in order to make things consists between runs, will 
			// dispatch base on factory order.
			for( int i=0, size=factories.size(); i<size; i++ ){
				final AnnotationProcessorFactory factory = factories.get(i);
				final Set<AnnotationTypeDeclaration> annotationTypes = batchFactory2Annos.get(factory);
				if( annotationTypes == null ) continue;
				final AnnotationProcessor processor = 
					factory.getProcessorFor(annotationTypes, processorEnv);
				if( processor != null ){
					if ( AptPlugin.DEBUG ) 
						trace( "runAPT: invoking batch processor " + processor.getClass().getName(), //$NON-NLS-1$
								processorEnv); 
                    processorEnv.setLatestProcessor(processor);
					processor.process();
				}
			}
		}
		
		// Now, do the file based dispatch
		if( !fileFactory2Annos.isEmpty() ){
			for( int fileIndex=0, numFiles=files.length; fileIndex<numFiles; fileIndex ++ ){
				final Set<AnnotationTypeDeclaration> annotationTypesInFile = file2AnnotationDecls.get(files[fileIndex]);
				if( annotationTypesInFile == null || annotationTypesInFile.isEmpty() )
					continue;
				for( int i=0, size=factories.size(); i<size; i++ ){
					final AnnotationProcessorFactory factory = factories.get(i);
					final Set<AnnotationTypeDeclaration> annotationTypesForFactory = fileFactory2Annos.get(factory);
					if( annotationTypesForFactory == null || annotationTypesForFactory.isEmpty() ) 
						continue;
					final Set<AnnotationTypeDeclaration> intersect = setIntersect(annotationTypesInFile, annotationTypesForFactory);
					if( intersect != null && !intersect.isEmpty() ){
						processorEnv.setFileProcessing(files[fileIndex]);
						final AnnotationProcessor processor = 
							factory.getProcessorFor(intersect, processorEnv);
						if( processor != null ){
							if ( AptPlugin.DEBUG ) 
								trace( "runAPT: invoking file-based processor " + processor.getClass().getName(), //$NON-NLS-1$
										processorEnv );
		                    processorEnv.setLatestProcessor(processor);
							processor.process();
						}
					}
				}
			}
		}
	}
	
	private APTResult runAPT(
			final List<AnnotationProcessorFactory> factories,
			final ProcessorEnvImpl processorEnv) 
	{
		final List<IFile> filesWithMissingType = new ArrayList<IFile>();
		final List<char[]> sourceForFiles = new ArrayList<char[]>();
		final APTResult result = runAPT(factories, processorEnv, filesWithMissingType, sourceForFiles);
	
		//APTResult lastResult = result;
		if( processorEnv.getPhase() == Phase.BUILD )
		{	
			boolean generatedTypes = result.hasGeneratedTypes();
			while( generatedTypes && !filesWithMissingType.isEmpty() ){
				// compile all generated files and try to satisfy the missing generated types.
				//recompileGeneratedFiles(result.getNewFiles());
				
				final int numFiles = filesWithMissingType.size();
				assert numFiles == sourceForFiles.size() :
					"size mismatch"; //$NON-NLS-1$			
				// we are about to re-process the file, wipe out the problems and
				// type dependencies recorded from the previous run.
				for( IFile file :  filesWithMissingType ){
					result.removeDependenciesFrom(file);
					result.removeProblemsFrom(file);
				}
				
				final IFile[] files = new IFile[numFiles];
				final char[][] sources = new char[numFiles][];
				for(int i=0; i<numFiles; i++ ){
					files[i] = filesWithMissingType.get(i);
					sources[i] = sourceForFiles.get(i);
				}
				ProcessorEnvImpl newEnv = ProcessorEnvImpl.newProcessorEnvironmentForBuild(
						files, sources, processorEnv.getJavaProject() );

				filesWithMissingType.clear();
				sourceForFiles.clear();
				APTResult newResult = runAPT(factories, newEnv, filesWithMissingType, sourceForFiles);
				// Only have generated types if there are *new* generated files
				generatedTypes = hasNewFiles(result, newResult);
				
				result.merge(newResult);
				newEnv.close();
			}
		}
	
		return result;
	}
	
	/**
	 * Diff the sets of files -- if the new result has
	 * files that the old one does not, we have new files.
	 */
	private boolean hasNewFiles(APTResult oldResult, APTResult newResult) {
		Set<IFile> oldFiles = oldResult.getNewFiles();
		Set<IFile> newFiles = newResult.getNewFiles();
		for (IFile file : newFiles) {
			if (!oldFiles.contains(file)) {
				return true;
			}
		}
		return false;
	}
	
	private APTResult runAPT(
			final List<AnnotationProcessorFactory> factories,
			final ProcessorEnvImpl processorEnv,
			final List<IFile> filesWithMissingType,
			final List<char[]> sourceForFiles) 
	{
		try {
			if (factories.size() == 0)
			{
				if ( AptPlugin.DEBUG ) 
					trace( "runAPT: leaving early because there are no factories", //$NON-NLS-1$ 
							processorEnv );
				return EMPTY_APT_RESULT;
			}
			// TODO: put the short circuit back in!!! (theodora)
			/*			
			if ( ! processorEnv.getFile().exists() )
			{
				if ( AptPlugin.DEBUG ) trace( "runAPT: leaving early because file doesn't exist"); //$NON-NLS-1$
				return EMPTY_APT_RESULT;
			}				
			*/
			final IFile[] files = processorEnv.getFiles();
			GeneratedFileManager gfm = GeneratedFileManager.getGeneratedFileManager( processorEnv.getJavaProject().getProject() );
			final Set<IFile> lastGeneratedFiles = new HashSet<IFile>();
			for( int i=0, len=files.length; i<len; i++ ){
				final Set<IFile> genFiles = gfm.getGeneratedFilesForParent( files[i] );
				if( genFiles != null )
					lastGeneratedFiles.addAll(genFiles);
			}
			
			
			boolean mixedModeDispatch = shouldDispatchToBatchProcessor(factories, processorEnv);
			if( mixedModeDispatch )
				runAPTInMixedMode(factories, processorEnv, filesWithMissingType, sourceForFiles);
			else
				runAPTInFileBasedMode(factories, processorEnv, filesWithMissingType, sourceForFiles);
			

			// notify the processor listeners
			final Set<AnnotationProcessorListener> listeners = processorEnv
					.getProcessorListeners();
			for (AnnotationProcessorListener listener : listeners) {
				EclipseRoundCompleteEvent event = null;
				if (listener instanceof RoundCompleteListener) {
					if (event == null)
						event = new EclipseRoundCompleteEvent(processorEnv);
					final RoundCompleteListener rcListener = (RoundCompleteListener) listener;
					rcListener.roundComplete(event);
				}
			}

			final Set<IFile> allGeneratedFiles = new HashSet<IFile>();
			Set<IFile> modifiedFiles = new HashSet<IFile>();
			Map<IFile, Boolean> filesMap = processorEnv.getGeneratedFiles();
			for (Map.Entry<IFile, Boolean> entry : filesMap.entrySet()) {
				allGeneratedFiles.add(entry.getKey());
				if (entry.getValue()) {
					modifiedFiles.add(entry.getKey());
				}
			}
			
			// any files that were generated for this parent on the last
			// run, but are no longer generated should be removed
			
			// BUGZILLA 103183 - reconcile-path disabled until type-generation in reconcile is turned on
			Set<IFile> allDeletedFiles = new HashSet<IFile>();
			for( int i=0, len=files.length; i<len; i++ ){
				final Set<IFile> deletedFiles = cleanupNoLongerGeneratedFiles( 
							files[i], 
							processorEnv.getCompilationUnit(), 
							lastGeneratedFiles, 
							allGeneratedFiles, 
							gfm,
							processorEnv);
				if(deletedFiles != null )
					allDeletedFiles.addAll(deletedFiles);		
			}		
			
			APTResult result = new APTResult( modifiedFiles, 
											  allDeletedFiles, 
											  processorEnv.getTypeDependencies(), 
											  processorEnv.getProblems(), 
											  processorEnv.getSourcePathChanged(),
											  processorEnv.hasGeneratedClassFiles() || processorEnv.hasGeneratedSourceFiles());
			processorEnv.close();
			return result;

			// log unclaimed annotations.
		} catch (Throwable t) {
			AptPlugin.log(t, "Unexpected failure running APT " + getFileNamesForPrinting(processorEnv)); //$NON-NLS-1$
		}
		return EMPTY_APT_RESULT;
	}
	
	/**
	 * @param one
	 * @param two
	 * @return the set intersect of the two given sets
	 */
	private Set<AnnotationTypeDeclaration> setIntersect(Set<AnnotationTypeDeclaration> one, Set<AnnotationTypeDeclaration> two ){
		Set<AnnotationTypeDeclaration> intersect = null;	
		for( AnnotationTypeDeclaration obj : one ){
			if( two.contains(obj) ){
				if( intersect == null )
					intersect = new HashSet<AnnotationTypeDeclaration>();
				intersect.add(obj);
			}
		}
		return intersect;
	}

	private Set<IFile> cleanupAllGeneratedFilesForParent( 
			IFile parent, 
			ICompilationUnit parentCompilationUnit,
			ProcessorEnvImpl processorEnv)
	{
		GeneratedFileManager gfm = GeneratedFileManager.getGeneratedFileManager( parent.getProject() );
		Set<IFile> lastGeneratedFiles = gfm.getGeneratedFilesForParent( parent );
		return cleanupNoLongerGeneratedFiles( 
				parent, 
				parentCompilationUnit, 
				lastGeneratedFiles, 
				Collections.<IFile>emptySet(), 
				gfm,
				processorEnv);
	}
	
	private Set<IFile> cleanupNoLongerGeneratedFiles( 
		IFile parentFile, 
		ICompilationUnit parentCompilationUnit, 
		Set<IFile> lastGeneratedFiles, Set<IFile> newGeneratedFiles,
		GeneratedFileManager gfm,
		ProcessorEnvImpl processorEnv)
	{
		HashSet<IFile> deletedFiles = new HashSet<IFile>();
			
		// make a copy into an array to avoid concurrent modification exceptions
		IFile[] files = lastGeneratedFiles.toArray( new IFile[ lastGeneratedFiles.size() ] );
		for ( int i = 0; i< files.length; i++ )
		{
			IFile f = files[i];
			if ( ! newGeneratedFiles.contains( f ) )
			{
				if ( AptPlugin.DEBUG ) 
					trace( "runAPT:  File " + f + " is no longer a generated file for " + parentFile,  //$NON-NLS-1$ //$NON-NLS-2$
							processorEnv );
				try
				{
					if ( parentCompilationUnit == null )
					{
						if ( gfm.deleteGeneratedFile( f, parentFile, null ) )
							deletedFiles.add( f );
					}
					else 
					{
						if ( gfm.deleteGeneratedTypeInMemory( f, parentCompilationUnit, null ) )
							deletedFiles.add( f );
					}
				}
				catch ( CoreException ce )
				{
					AptPlugin.log(ce, "Could not clean up generated files"); //$NON-NLS-1$
				}
			}
		}
		return deletedFiles;
	}
	
	/**
	 * invoking annotation processors respecting apt semantics.
	 */
	private static void checkAnnotations(
			final List<AnnotationProcessorFactory> factories,
			final Map<String, AnnotationTypeDeclaration> declarations,
			final ProcessorEnvImpl env) {
		for (int i = 0, size = factories.size(); i < size; i++) {
			final AnnotationProcessorFactory factory = factories.get(i);
			final Set<AnnotationTypeDeclaration> factoryDecls = getFactorySupportedAnnotations(
					factory, declarations);
			final AnnotationProcessor processor = factory.getProcessorFor(
					factoryDecls, env);
			processor.process();
			if (declarations.isEmpty())
				return;
		}
		// log unclaimed annotations.
	}	

	/**
	 * @return the set of {@link AnnotationTypeDeclaration} that {@link #factory} supports or null
	 *         if the factory doesn't support any of the declarations.
	 *         If the factory supports "*", then the empty set will be returned
	 *
	 * This method will destructively modify {@link #declarations}. Entries will be removed from
	 * {@link #declarations} as the declarations are being added into the returned set.
	 */
	private static Set<AnnotationTypeDeclaration> getFactorySupportedAnnotations(
			final AnnotationProcessorFactory factory,
			final Map<String, AnnotationTypeDeclaration> declarations)

	{
		final Collection<String> supportedTypes = factory
				.supportedAnnotationTypes();

		if (supportedTypes == null || supportedTypes.size() == 0)
			return Collections.emptySet();

		final Set<AnnotationTypeDeclaration> fDecls = new HashSet<AnnotationTypeDeclaration>();

		for (Iterator<String> it = supportedTypes.iterator(); it.hasNext();) {
			final String typeName = it.next();
			if (typeName.equals("*")) { //$NON-NLS-1$
				declarations.clear();
				return Collections.emptySet();
			} else if (typeName.endsWith("*")) { //$NON-NLS-1$
				final String prefix = typeName.substring(0,
						typeName.length() - 2);
				for (Iterator<Map.Entry<String, AnnotationTypeDeclaration>> entries = declarations
						.entrySet().iterator(); entries.hasNext();) {
					final Map.Entry<String, AnnotationTypeDeclaration> entry = entries
							.next();
					final String key = entry.getKey();
					if (key.startsWith(prefix)) {
						fDecls.add(entry.getValue());
						entries.remove();
					}
				}
			} else {
				final AnnotationTypeDeclaration decl = declarations
						.get(typeName);
				if (decl != null) {
					fDecls.add(decl);
					declarations.remove(typeName);
				}
			}
		}
		return fDecls.isEmpty() ? null : fDecls;
	}
	
	private static void trace( String s, ProcessorEnvImpl processorEnv )
	{
		if (AptPlugin.DEBUG)
		{
			if (processorEnv != null) {
				s = "[ phase = " + processorEnv.getPhase() + ", file = " + getFileNamesForPrinting(processorEnv) +" ]  " + s; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			System.out.println( "[" + APTDispatch.class.getName() + "][ thread= " + Thread.currentThread().getName() + " ]"+ s ); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
	}
	
	/**
	 * For debugging statements only!!
	 * @return the names of the files that we are currently processing. 
	 */
	private static String getFileNamesForPrinting(final ProcessorEnvImpl processorEnv){
		if( processorEnv != null ){
			final IFile file = processorEnv.getFile();
			if( file != null )
				return file.getName();
		}
		final IFile[] files = processorEnv.getFiles();
		final int len = files.length;
		switch( len )
		{
		case 0:
			return "no file(s)"; //$NON-NLS-1$
		case 1:
			return files[0].getName();
		default:
			StringBuilder sb = new StringBuilder();
			boolean firstItem = true;
			for (IFile file : files) {
				if (firstItem) {
					firstItem = false;
				}
				else {
					sb.append(", "); //$NON-NLS-1$
				}
				sb.append(file.getName());
			}
			return sb.toString();
		}
	}
}
