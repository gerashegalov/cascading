/*
 * Copyright (c) 2007-2014 Concurrent, Inc. All Rights Reserved.
 *
 * Project and contact information: http://www.cascading.org/
 *
 * This file is part of the Cascading project.
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
 * limitations under the License.
 */

package cascading.tap.hadoop;

import java.beans.ConstructorProperties;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import cascading.flow.FlowProcess;
import cascading.flow.hadoop.util.HadoopUtil;
import cascading.scheme.Scheme;
import cascading.scheme.hadoop.SequenceFile;
import cascading.tap.SinkMode;
import cascading.tap.Tap;
import cascading.tap.TapException;
import cascading.tap.hadoop.io.CombineFileRecordReaderWrapper;
import cascading.tap.hadoop.io.HadoopTupleEntrySchemeCollector;
import cascading.tap.hadoop.io.HadoopTupleEntrySchemeIterator;
import cascading.tap.type.FileType;
import cascading.tuple.Fields;
import cascading.tuple.TupleEntryCollector;
import cascading.tuple.TupleEntryIterator;
import cascading.tuple.hadoop.TupleSerialization;
import cascading.util.Util;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.fs.s3native.NativeS3FileSystem;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.Utils;
import org.apache.hadoop.mapred.lib.CombineFileInputFormat;
import org.apache.hadoop.mapred.lib.CombineFileRecordReader;
import org.apache.hadoop.mapred.lib.CombineFileSplit;
import org.apache.hadoop.util.Progressable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class Hfs is the base class for all Hadoop file system access. Hfs may only be used with the
 * {@link cascading.flow.hadoop.HadoopFlowConnector} when creating Hadoop executable {@link cascading.flow.Flow}
 * instances.
 * <p/>
 * Paths typically should point to a directory, where in turn all the "part" files immediately in that directory will
 * be included. This is the practice Hadoop expects. Sub-directories are not included and typically result in a failure.
 * <p/>
 * To include sub-directories, Hadoop supports "globing". Globing is a frustrating feature and is supported more
 * robustly by {@link GlobHfs} and less so by Hfs.
 * <p/>
 * Hfs will accept {@code /*} (wildcard) paths, but not all convenience methods like
 * {@link #getSize(org.apache.hadoop.mapred.JobConf)} will behave properly or reliably. Nor can the Hfs instance
 * with a wildcard path be used as a sink to write data.
 * <p/>
 * In those cases use GlobHfs since it is a sub-class of {@link cascading.tap.MultiSourceTap}.
 * <p/>
 * Optionally use {@link Dfs} or {@link Lfs} for resources specific to Hadoop Distributed file system or
 * the Local file system, respectively. Using Hfs is the best practice when possible, Lfs and Dfs are conveniences.
 * <p/>
 * Use the Hfs class if the 'kind' of resource is unknown at design time. To use, prefix a scheme to the 'stringPath'. Where
 * <code>hdfs://...</code> will denote Dfs, and <code>file://...</code> will denote Lfs.
 * <p/>
 * Call {@link #setTemporaryDirectory(java.util.Map, String)} to use a different temporary file directory path
 * other than the current Hadoop default path.
 * <p/>
 * By default Cascading on Hadoop will assume any source or sink Tap using the {@code file://} URI scheme
 * intends to read files from the local client filesystem (for example when using the {@code Lfs} Tap) where the Hadoop
 * job jar is started, Tap so will force any MapReduce jobs reading or writing to {@code file://} resources to run in
 * Hadoop "standalone mode" so that the file can be read.
 * <p/>
 * To change this behavior, {@link HfsProps#setLocalModeScheme(java.util.Map, String)} to set a different scheme value,
 * or to "none" to disable entirely for the case the file to be read is available on every Hadoop processing node
 * in the exact same path.
 * <p/>
 * Hfs can optionally combine multiple small files (or a series of small "blocks") into larger "splits". This reduces
 * the number of resulting map tasks created by Hadoop and can improve application performance.
 * <p/>
 * This is enabled by calling {@link HfsProps#setUseCombinedInput(boolean)} to {@code true}. By default, merging
 * or combining splits into large ones is disabled.
 */
public class Hfs extends Tap<JobConf, RecordReader, OutputCollector> implements FileType<JobConf>
  {
  /** Field LOG */
  private static final Logger LOG = LoggerFactory.getLogger( Hfs.class );

  /**
   * Field TEMPORARY_DIRECTORY
   *
   * @deprecated see {@link HfsProps#TEMPORARY_DIRECTORY}
   */
  @Deprecated
  public static final String TEMPORARY_DIRECTORY = HfsProps.TEMPORARY_DIRECTORY;

  /** Field stringPath */
  protected String stringPath;
  /** Field uriScheme */
  transient URI uriScheme;
  /** Field path */
  transient Path path;
  /** Field paths */
  private transient FileStatus[] statuses; // only used by getModifiedTime

  private boolean useDistCache;

  /**
   * Method setTemporaryDirectory sets the temporary directory on the given properties object.
   *
   * @param properties of type Map<Object,Object>
   * @param tempDir    of type String
   * @deprecated see {@link HfsProps}
   */
  @Deprecated
  public static void setTemporaryDirectory( Map<Object, Object> properties, String tempDir )
    {
    properties.put( HfsProps.TEMPORARY_DIRECTORY, tempDir );
    }

  /**
   * Method getTemporaryDirectory returns the configured temporary directory from the given properties object.
   *
   * @param properties of type Map<Object,Object>
   * @return a String or null if not set
   * @deprecated see {@link HfsProps}
   */
  @Deprecated
  public static String getTemporaryDirectory( Map<Object, Object> properties )
    {
    return (String) properties.get( HfsProps.TEMPORARY_DIRECTORY );
    }

  protected static String getLocalModeScheme( JobConf conf, String defaultValue )
    {
    return conf.get( HfsProps.LOCAL_MODE_SCHEME, defaultValue );
    }

  protected static boolean getUseCombinedInput( JobConf conf )
    {
    return conf.getBoolean( HfsProps.COMBINE_INPUT_FILES, false );
    }

  protected static boolean getCombinedInputSafeMode( JobConf conf )
    {
    return conf.getBoolean( HfsProps.COMBINE_INPUT_FILES_SAFE_MODE, true );
    }

  protected Hfs()
    {
    }

  @ConstructorProperties({"scheme"})
  protected Hfs( Scheme<JobConf, RecordReader, OutputCollector, ?, ?> scheme )
    {
    super( scheme );
    }

  /**
   * Constructor Hfs creates a new Hfs instance.
   *
   * @param fields     of type Fields
   * @param stringPath of type String
   */
  @Deprecated
  @ConstructorProperties({"fields", "stringPath"})
  public Hfs( Fields fields, String stringPath )
    {
    super( new SequenceFile( fields ) );
    setStringPath( stringPath );
    }

  /**
   * Constructor Hfs creates a new Hfs instance.
   *
   * @param fields     of type Fields
   * @param stringPath of type String
   * @param replace    of type boolean
   */
  @Deprecated
  @ConstructorProperties({"fields", "stringPath", "replace"})
  public Hfs( Fields fields, String stringPath, boolean replace )
    {
    super( new SequenceFile( fields ), replace ? SinkMode.REPLACE : SinkMode.KEEP );
    setStringPath( stringPath );
    }

  /**
   * Constructor Hfs creates a new Hfs instance.
   *
   * @param fields     of type Fields
   * @param stringPath of type String
   * @param sinkMode   of type SinkMode
   */
  @Deprecated
  @ConstructorProperties({"fields", "stringPath", "sinkMode"})
  public Hfs( Fields fields, String stringPath, SinkMode sinkMode )
    {
    super( new SequenceFile( fields ), sinkMode );
    setStringPath( stringPath );

    if( sinkMode == SinkMode.UPDATE )
      throw new IllegalArgumentException( "updates are not supported" );
    }

  /**
   * Constructor Hfs creates a new Hfs instance.
   *
   * @param scheme     of type Scheme
   * @param stringPath of type String
   */
  @ConstructorProperties({"scheme", "stringPath"})
  public Hfs( Scheme<JobConf, RecordReader, OutputCollector, ?, ?> scheme, String stringPath )
    {
    super( scheme );
    setStringPath( stringPath );
    }

  /**
   * Constructor Hfs creates a new Hfs instance.
   *
   * @param scheme     of type Scheme
   * @param stringPath of type String
   * @param replace    of type boolean
   */
  @Deprecated
  @ConstructorProperties({"scheme", "stringPath", "replace"})
  public Hfs( Scheme<JobConf, RecordReader, OutputCollector, ?, ?> scheme, String stringPath, boolean replace )
    {
    super( scheme, replace ? SinkMode.REPLACE : SinkMode.KEEP );
    setStringPath( stringPath );
    }

  /**
   * Constructor Hfs creates a new Hfs instance.
   *
   * @param scheme     of type Scheme
   * @param stringPath of type String
   * @param sinkMode   of type SinkMode
   */
  @ConstructorProperties({"scheme", "stringPath", "sinkMode"})
  public Hfs( Scheme<JobConf, RecordReader, OutputCollector, ?, ?> scheme, String stringPath, SinkMode sinkMode )
    {
    super( scheme, sinkMode );
    setStringPath( stringPath );
    }

  protected void setStringPath( String stringPath )
    {
    this.stringPath = Util.normalizeUrl( stringPath );
    }

  protected void setUriScheme( URI uriScheme )
    {
    this.uriScheme = uriScheme;
    }

  public URI getURIScheme( JobConf jobConf )
    {
    if( uriScheme != null )
      return uriScheme;

    uriScheme = makeURIScheme( jobConf );

    return uriScheme;
    }

  protected URI makeURIScheme( JobConf jobConf )
    {
    try
      {
      URI uriScheme;

      LOG.debug( "handling path: {}", stringPath );

      URI uri = new Path( stringPath ).toUri(); // safer URI parsing
      String schemeString = uri.getScheme();
      String authority = uri.getAuthority();

      LOG.debug( "found scheme: {}, authority: {}", schemeString, authority );

      if( schemeString != null && authority != null )
        uriScheme = new URI( schemeString + "://" + uri.getAuthority() );
      else if( schemeString != null )
        uriScheme = new URI( schemeString + ":///" );
      else
        uriScheme = getDefaultFileSystemURIScheme( jobConf );

      LOG.debug( "using uri scheme: {}", uriScheme );

      return uriScheme;
      }
    catch( URISyntaxException exception )
      {
      throw new TapException( "could not determine scheme from path: " + getPath(), exception );
      }
    }

  /**
   * Method getDefaultFileSystemURIScheme returns the URI scheme for the default Hadoop FileSystem.
   *
   * @param jobConf of type JobConf
   * @return URI
   */
  public URI getDefaultFileSystemURIScheme( JobConf jobConf )
    {
    return getDefaultFileSystem( jobConf ).getUri();
    }

  protected FileSystem getDefaultFileSystem( JobConf jobConf )
    {
    try
      {
      return FileSystem.get( jobConf );
      }
    catch( IOException exception )
      {
      throw new TapException( "unable to get handle to underlying filesystem", exception );
      }
    }

  protected FileSystem getFileSystem( JobConf jobConf )
    {
    URI scheme = getURIScheme( jobConf );

    try
      {
      return FileSystem.get( scheme, jobConf );
      }
    catch( IOException exception )
      {
      throw new TapException( "unable to get handle to get filesystem for: " + scheme.getScheme(), exception );
      }
    }

  @Override
  public String getIdentifier()
    {
    return getPath().toString();
    }

  public Path getPath()
    {
    if( path != null )
      return path;

    if( stringPath == null )
      throw new IllegalStateException( "path not initialized" );

    path = new Path( stringPath );

    return path;
    }

  @Override
  public String getFullIdentifier( JobConf conf )
    {
    return getPath().makeQualified( getFileSystem( conf ) ).toString();
    }

  @Override
  public void sourceConfInit( FlowProcess<JobConf> process, JobConf conf )
    {
    String fullIdentifier = getFullIdentifier( conf );

    applySourceConfInitIdentifiers( process, conf, fullIdentifier );

    verifyNoDuplicates( conf );
    }

  protected static void verifyNoDuplicates( JobConf conf )
    {
    Path[] inputPaths = FileInputFormat.getInputPaths( conf );
    Set<Path> paths = new HashSet<Path>( (int) ( inputPaths.length / .75f ) );

    for( Path inputPath : inputPaths )
      {
      if( !paths.add( inputPath ) )
        throw new TapException( "may not add duplicate paths, found: " + inputPath );
      }
    }

  protected void applySourceConfInitIdentifiers( FlowProcess<JobConf> process, JobConf conf, String... fullIdentifiers )
    {
    for( String fullIdentifier : fullIdentifiers )
      sourceConfInitAddInputPath( conf, new Path( fullIdentifier ) );

    sourceConfInitComplete( process, conf );
    }

  protected void sourceConfInitAddInputPath( JobConf conf, Path qualifiedPath )
    {
    if( useDistCache )
      {
      URI uri = qualifiedPath.toUri();

      FileSystem fs;
      try
        {
        fs = FileSystem.get( uri, conf );
        FileStatus[] stats = fs.listStatus( qualifiedPath );
        if( stats == null || stats.length == 0 )
          FileInputFormat.addInputPath( conf, qualifiedPath);
        else
          for( FileStatus ifs : stats )
            {
            URI cacheUri = fs.makeQualified( ifs.getPath() ).toUri();
            String cachePathStr = String.format( "%s://%s-%s/%s",
                DistributedCacheFileSystem.DCFS_SCHEME, cacheUri.getScheme(),
                cacheUri.getAuthority(), cacheUri.getPath() );
            FileInputFormat.addInputPath( conf, new Path( cachePathStr ) );
            DistributedCache.addCacheFile( cacheUri, conf );
            }
        }
      catch( IOException infeasible )
        {
        throw new IllegalArgumentException( uri + ": fileSystem failure",
            infeasible );
        }
      }
    else
      FileInputFormat.addInputPath( conf, qualifiedPath);

    makeLocal( conf, qualifiedPath, "forcing job to local mode, via source: " );
    }

  protected void sourceConfInitComplete( FlowProcess<JobConf> process, JobConf conf )
    {
    super.sourceConfInit( process, conf );

    TupleSerialization.setSerializations( conf ); // allows Hfs to be used independent of Flow

    // use CombineFileInputFormat if that is enabled
    handleCombineFileInputFormat( conf );
    }

  /**
   * Based on the configuration, handles and sets {@link CombineFileInputFormat} as the input
   * format.
   */
  private void handleCombineFileInputFormat( JobConf conf )
    {
    // if combining files, override the configuration to use CombineFileInputFormat
    if( !getUseCombinedInput( conf ) )
      return;

    // get the prescribed individual input format from the underlying scheme so it can be used by CombinedInputFormat
    String individualInputFormat = conf.get( "mapred.input.format.class" );

    if( individualInputFormat == null )
      throw new TapException( "input format is missing from the underlying scheme" );

    if( individualInputFormat.equals( CombinedInputFormat.class.getName() ) &&
      conf.get( CombineFileRecordReaderWrapper.INDIVIDUAL_INPUT_FORMAT ) == null )
      throw new TapException( "the input format class is already the combined input format but the underlying input format is missing" );

    // if safe mode is on (default) throw an exception if the InputFormat is not a FileInputFormat, otherwise log a
    // warning and don't use the CombineFileInputFormat
    boolean safeMode = getCombinedInputSafeMode( conf );

    if( !FileInputFormat.class.isAssignableFrom( conf.getClass( "mapred.input.format.class", null ) ) )
      {
      if( safeMode )
        throw new TapException( "input format must be of type org.apache.hadoop.mapred.FileInputFormat, got: " + individualInputFormat );
      else
        LOG.warn( "not combining input splits with CombineFileInputFormat, {} is not of type org.apache.hadoop.mapred.FileInputFormat.", individualInputFormat );
      }
    else
      {
      // set the underlying individual input format
      conf.set( CombineFileRecordReaderWrapper.INDIVIDUAL_INPUT_FORMAT, individualInputFormat );

      // override the input format class
      conf.setInputFormat( CombinedInputFormat.class );
      }
    }

  @Override
  public void sinkConfInit( FlowProcess<JobConf> process, JobConf conf )
    {
    Path qualifiedPath = new Path( getFullIdentifier( conf ) );

    FileOutputFormat.setOutputPath( conf, qualifiedPath );
    super.sinkConfInit( process, conf );

    makeLocal( conf, qualifiedPath, "forcing job to local mode, via sink: " );

    TupleSerialization.setSerializations( conf ); // allows Hfs to be used independent of Flow
    }

  private void makeLocal( JobConf conf, Path qualifiedPath, String infoMessage )
    {
    String scheme = getLocalModeScheme( conf, "file" );

    if( !HadoopUtil.isLocal( conf ) && qualifiedPath.toUri().getScheme().equalsIgnoreCase( scheme ) )
      {
      if( LOG.isInfoEnabled() )
        LOG.info( infoMessage + toString() );

      HadoopUtil.setLocal( conf ); // force job to run locally
      }
    }

  @Override
  public TupleEntryIterator openForRead( FlowProcess<JobConf> flowProcess, RecordReader input ) throws IOException
    {
    // input may be null when this method is called on the client side or cluster side when accumulating
    // for a HashJoin
    return new HadoopTupleEntrySchemeIterator( flowProcess, this, input );
    }

  @Override
  public TupleEntryCollector openForWrite( FlowProcess<JobConf> flowProcess, OutputCollector output ) throws IOException
    {
    // output may be null when this method is called on the client side or cluster side when creating
    // side files with the TemplateTap
    return new HadoopTupleEntrySchemeCollector( flowProcess, this, output );
    }

  @Override
  public boolean createResource( JobConf conf ) throws IOException
    {
    if( LOG.isDebugEnabled() )
      LOG.debug( "making dirs: {}", getFullIdentifier( conf ) );

    return getFileSystem( conf ).mkdirs( getPath() );
    }

  @Override
  public boolean deleteResource( JobConf conf ) throws IOException
    {
    String fullIdentifier = getFullIdentifier( conf );

    return deleteFullIdentifier( conf, fullIdentifier );
    }

  private boolean deleteFullIdentifier( JobConf conf, String fullIdentifier ) throws IOException
    {
    if( LOG.isDebugEnabled() )
      LOG.debug( "deleting: {}", fullIdentifier );

    Path fullPath = new Path( fullIdentifier );

    // do not delete the root directory
    if( fullPath.depth() == 0 )
      return true;

    FileSystem fileSystem = getFileSystem( conf );

    try
      {
      return fileSystem.delete( fullPath, true );
      }
    catch( NullPointerException exception )
      {
      // hack to get around npe thrown when fs reaches root directory
      if( !( fileSystem instanceof NativeS3FileSystem ) )
        throw exception;
      }

    return true;
    }

  public boolean deleteChildResource( JobConf conf, String childIdentifier ) throws IOException
    {
    Path childPath = new Path( childIdentifier ).makeQualified( getFileSystem( conf ) );

    if( !childPath.toString().startsWith( getFullIdentifier( conf ) ) )
      return false;

    return deleteFullIdentifier( conf, childPath.toString() );
    }


  @Override
  public boolean resourceExists( JobConf conf ) throws IOException
    {
    // unfortunately getFileSystem( conf ).exists( getPath() ); does not account for "/*" etc
    // nor is there an more efficient means to test for existence
    FileStatus[] fileStatuses = getFileSystem( conf ).globStatus( getPath() );

    return fileStatuses != null && fileStatuses.length > 0;
    }

  @Override
  public boolean isDirectory( JobConf conf ) throws IOException
    {
    if( !resourceExists( conf ) )
      return false;

    return getFileSystem( conf ).getFileStatus( getPath() ).isDir();
    }

  @Override
  public long getSize( JobConf conf ) throws IOException
    {
    if( !resourceExists( conf ) )
      return 0;

    FileStatus fileStatus = getFileSystem( conf ).getFileStatus( getPath() );

    if( fileStatus.isDir() )
      return 0;

    return getFileSystem( conf ).getFileStatus( getPath() ).getLen();
    }

  /**
   * Method getBlockSize returns the {@code blocksize} specified by the underlying file system for this resource.
   *
   * @param conf of JobConf
   * @return long
   * @throws IOException when
   */
  public long getBlockSize( JobConf conf ) throws IOException
    {
    if( !resourceExists( conf ) )
      return 0;

    FileStatus fileStatus = getFileSystem( conf ).getFileStatus( getPath() );

    if( fileStatus.isDir() )
      return 0;

    return fileStatus.getBlockSize();
    }

  /**
   * Method getReplication returns the {@code replication} specified by the underlying file system for
   * this resource.
   *
   * @param conf of JobConf
   * @return int
   * @throws IOException when
   */
  public int getReplication( JobConf conf ) throws IOException
    {
    if( !resourceExists( conf ) )
      return 0;

    FileStatus fileStatus = getFileSystem( conf ).getFileStatus( getPath() );

    if( fileStatus.isDir() )
      return 0;

    return fileStatus.getReplication();
    }

  @Override
  public String[] getChildIdentifiers( JobConf conf ) throws IOException
    {
    return getChildIdentifiers( conf, 1, false );
    }

  @Override
  public String[] getChildIdentifiers( JobConf conf, int depth, boolean fullyQualified ) throws IOException
    {
    if( !resourceExists( conf ) )
      return new String[ 0 ];

    if( depth == 0 && !fullyQualified )
      return new String[]{getIdentifier()};

    String fullIdentifier = getFullIdentifier( conf );

    int trim = fullyQualified ? 0 : fullIdentifier.length() + 1;

    Set<String> results = new LinkedHashSet<String>();

    getChildPaths( conf, results, trim, new Path( fullIdentifier ), depth );

    return results.toArray( new String[ results.size() ] );
    }

  private void getChildPaths( JobConf conf, Set<String> results, int trim, Path path, int depth ) throws IOException
    {
    if( depth == 0 )
      {
      String substring = path.toString().substring( trim );
      String identifier = getIdentifier();

      if( identifier == null || identifier.isEmpty() )
        results.add( new Path( substring ).toString() );
      else
        results.add( new Path( identifier, substring ).toString() );

      return;
      }

    FileStatus[] statuses = getFileSystem( conf ).listStatus( path, new Utils.OutputFileUtils.OutputFilesFilter() );

    if( statuses == null )
      return;

    for( FileStatus fileStatus : statuses )
      getChildPaths( conf, results, trim, fileStatus.getPath(), depth - 1 );
    }

  @Override
  public long getModifiedTime( JobConf conf ) throws IOException
    {
    if( !resourceExists( conf ) )
      return 0;

    FileStatus fileStatus = getFileSystem( conf ).getFileStatus( getPath() );

    if( !fileStatus.isDir() )
      return fileStatus.getModificationTime();

    // todo: this should ignore the _temporary path, or not cache if found in the array
    makeStatuses( conf );

    // statuses is empty, return 0
    if( statuses == null || statuses.length == 0 )
      return 0;

    long date = 0;

    // filter out directories as we don't recurs into sub dirs
    for( FileStatus status : statuses )
      {
      if( !status.isDir() )
        date = Math.max( date, status.getModificationTime() );
      }

    return date;
    }

  public static Path getTempPath( JobConf conf )
    {
    String tempDir = conf.get( HfsProps.TEMPORARY_DIRECTORY );

    if( tempDir == null )
      tempDir = conf.get( "hadoop.tmp.dir" );

    return new Path( tempDir );
    }

  protected String makeTemporaryPathDirString( String name )
    {
    // _ is treated as a hidden file, so wipe them out
    name = name.replaceAll( "^[_\\W\\s]+", "" );

    if( name.isEmpty() )
      name = "temp-path";

    return name.replaceAll( "[\\W\\s]+", "_" ) + Util.createUniqueID();
    }

  /**
   * Given a file-system object, it makes an array of paths
   *
   * @param conf of type JobConf
   * @throws IOException on failure
   */
  private void makeStatuses( JobConf conf ) throws IOException
    {
    if( statuses != null )
      return;

    statuses = getFileSystem( conf ).listStatus( getPath() );
    }

  /** Combined input format that uses the underlying individual input format to combine multiple files into a single split. */
  static class CombinedInputFormat extends CombineFileInputFormat implements Configurable
    {
    private Configuration conf;

    public RecordReader getRecordReader( InputSplit split, JobConf job, Reporter reporter ) throws IOException
      {
      return new CombineFileRecordReader( job, (CombineFileSplit) split, reporter, CombineFileRecordReaderWrapper.class );
      }

    @Override
    public void setConf( Configuration conf )
      {
      this.conf = conf;

      // set the aliased property value, if zero, the super class will look up the hadoop property
      setMaxSplitSize( conf.getLong( HfsProps.COMBINE_INPUT_FILES_SIZE_MAX, 0 ) );
      }

    @Override
    public Configuration getConf()
      {
      return conf;
      }
    }

    public Hfs withDistributedCache()
      {
      useDistCache = true;
      return this;
      }

  /**
   * FileSystem API to DistributedCache-based Hfs
   */
  public static class DistributedCacheFileSystem extends FileSystem
    {
    public static final String DCFS_SCHEME = "dcfs";
    public static final String DCFS_IMPL =
        String.format( "fs.%s.impl", DCFS_SCHEME );

    @Override
    public URI getUri()
      {
      throw new UnsupportedOperationException();
      }

    @Override
    public FSDataInputStream open( Path f, int bufferSize )
        throws IOException
      {
      Path[] localFiles = getLocalPaths( f );
      String cacheFileName = f.getName();

      for( Path p : localFiles )
        if (cacheFileName.equals( p.getName()) )
          return FileSystem.getLocal( getConf() ).open(p);

      throw new FileNotFoundException(f + " not found");
      }

    private Path[] getLocalPaths( Path f ) throws IOException
      {
      URI uri = f.toUri();

      if( !DCFS_SCHEME.equals(uri.getScheme()) )
        throw new IllegalArgumentException( uri + ": dcfs scheme expected" );

      String auth = uri.getAuthority();
      if( auth == null )
        throw new IllegalArgumentException( uri + ": No authority" );

      int dashPos = auth.indexOf( "-" );
      if( dashPos < 0 )
        throw new IllegalArgumentException( uri + ": No dash in authority" );

      Path[] localFiles = DistributedCache.getLocalCacheFiles( getConf() );
      if( localFiles == null )
        throw new FileNotFoundException( "No local cache files" );
      return localFiles;
      }

      @Override
    public FSDataOutputStream create(
        Path f,
        FsPermission permission,
        boolean overwrite,
        int bufferSize,
        short replication,
        long blockSize,
        Progressable progress)
        throws IOException
      {
      throw new UnsupportedOperationException();
      }

    @Override
    public FSDataOutputStream append(
        Path f,
        int bufferSize,
        Progressable progress)
        throws IOException
      {
      throw new UnsupportedOperationException();
      }

    @Override
    public boolean rename( Path src, Path dst ) throws IOException
      {
      throw new UnsupportedOperationException();
      }

    @Override
    public boolean delete( Path f ) throws IOException
      {
      throw new UnsupportedOperationException();
      }

    @Override
    public boolean delete( Path f, boolean recursive ) throws IOException
      {
      throw new UnsupportedOperationException();
      }

    @Override
    public FileStatus[] listStatus( Path f ) throws IOException
      {
      throw new UnsupportedOperationException();
      }

    @Override
    public void setWorkingDirectory( Path new_dir )
      {
      throw new UnsupportedOperationException();
      }

    @Override
    public Path getWorkingDirectory()
      {
      throw new UnsupportedOperationException();
      }

    @Override
    public boolean mkdirs( Path f, FsPermission permission ) throws IOException
      {
      throw new UnsupportedOperationException();
      }

    @Override
    public FileStatus getFileStatus( Path f ) throws IOException
      {
      Path[] localFiles = getLocalPaths( f );

      for( Path p : localFiles )
        if( f.getName().equals( p.getName() ) )
          return FileSystem.getLocal( getConf() ).getFileStatus( p );

      throw new FileNotFoundException( f + ": Not found!" );
      }
    }
  }
