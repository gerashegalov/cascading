/*
 * Copyright (c) 2007-2011 Concurrent, Inc. All Rights Reserved.
 *
 * Project and contact information: http://www.cascading.org/
 *
 * This file is part of the Cascading project.
 *
 * Cascading is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Cascading is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Cascading.  If not, see <http://www.gnu.org/licenses/>.
 */

package cascading.flow.hadoop;

import java.io.IOException;
import java.util.Map;

import cascading.flow.Flow;
import cascading.flow.FlowException;
import cascading.flow.FlowProcess;
import cascading.flow.planner.ElementGraph;
import cascading.flow.planner.FlowStep;
import cascading.flow.planner.StepGraph;
import cascading.tap.Tap;
import cascading.tap.hadoop.HttpFileSystem;
import cascading.util.Util;
import org.apache.hadoop.mapred.JobConf;

/**
 * <p/>
 * Flows are submitted in order of dependency. If two or more steps do not share the same dependencies and all
 * can be scheduled simultaneously, the {@link #getSubmitPriority()} value determines the order in which
 * all steps will be submitted for execution. The default submit priority is 5.
 */
public class HadoopFlow extends Flow<JobConf>
  {
  /** Field hdfsShutdown */
  private static Thread hdfsShutdown = null;
  /** Field shutdownCount */
  private static int shutdownCount = 0;
  /** Field jobConf */
  private transient JobConf jobConf;
  /** Field shutdownHook */
  private transient Thread shutdownHook;
  /** Field preserveTemporaryFiles */
  private boolean preserveTemporaryFiles = false;

  public HadoopFlow()
    {
    }

  /**
   * Property preserveTemporaryFiles forces the Flow instance to keep any temporary intermediate data sets. Useful
   * for debugging. Defaults to {@code false}.
   *
   * @param properties             of type Map
   * @param preserveTemporaryFiles of type boolean
   */
  public static void setPreserveTemporaryFiles( Map<Object, Object> properties, boolean preserveTemporaryFiles )
    {
    properties.put( "cascading.flow.preservetemporaryfiles", Boolean.toString( preserveTemporaryFiles ) );
    }

  /**
   * Returns property preserveTemporaryFiles.
   *
   * @param properties of type Map
   * @return a boolean
   */
  public static boolean getPreserveTemporaryFiles( Map<Object, Object> properties )
    {
    return Boolean.parseBoolean( Util.getProperty( properties, "cascading.flow.preservetemporaryfiles", "false" ) );
    }

  /**
   * Property jobPollingInterval will set the time to wait between polling the remote server for the status of a job.
   * The default value is 5000 msec (5 seconds).
   *
   * @param properties of type Map
   * @param interval   of type long
   */
  public static void setJobPollingInterval( Map<Object, Object> properties, long interval )
    {
    properties.put( "cascading.flow.job.pollinginterval", Long.toString( interval ) );
    }

  /**
   * Returns property jobPollingInterval. The default is 5000 (5 sec).
   *
   * @param properties of type Map
   * @return a long
   */
  public static long getJobPollingInterval( Map<Object, Object> properties )
    {
    return Long.parseLong( Util.getProperty( properties, "cascading.flow.job.pollinginterval", "500" ) );
    }

  public static long getJobPollingInterval( JobConf jobConf )
    {
    return jobConf.getLong( "cascading.flow.job.pollinginterval", 5000 );
    }

  protected HadoopFlow( Map<Object, Object> properties, JobConf jobConf, String name )
    {
    super( properties, jobConf, name );
    }

  protected HadoopFlow( Map<Object, Object> properties, JobConf jobConf, String name, ElementGraph pipeGraph, StepGraph stepGraph, Map<String, Tap> sources, Map<String, Tap> sinks, Map<String, Tap> traps )
    {
    super( properties, jobConf, name, pipeGraph, stepGraph, sources, sinks, traps );
    }

  protected HadoopFlow( Map<Object, Object> properties, JobConf jobConf, String name, StepGraph stepGraph, Map<String, Tap> sources, Map<String, Tap> sinks, Map<String, Tap> traps )
    {
    super( properties, jobConf, name, stepGraph, sources, sinks, traps );
    }

  protected void initFromProperties( Map<Object, Object> properties )
    {
    super.initFromProperties( properties );
    preserveTemporaryFiles = getPreserveTemporaryFiles( properties );
    }

  protected void setConfig( Map<Object, Object> properties, JobConf parentConfig )
    {
    if( parentConfig == null && properties != null )
      parentConfig = HadoopUtil.createJobConf( properties, HadoopPlanner.getJobConf( properties ) );

    if( parentConfig == null ) // this is ok, getJobConf will pass a default parent in
      return;

    this.jobConf = new JobConf( parentConfig ); // prevent local values from being shared
    this.jobConf.set( "fs.http.impl", HttpFileSystem.class.getName() );
    this.jobConf.set( "fs.https.impl", HttpFileSystem.class.getName() );

    // set the ID for future reference
    this.jobConf.set( "cascading.flow.id", getID() );
    }

  /**
   * Method getJobConf returns the jobConf of this Flow object.
   *
   * @return the jobConf (type JobConf) of this Flow object.
   */
  public JobConf getConfig()
    {
    if( jobConf == null )
      setConfig( null, new JobConf() );

    return jobConf;
    }

  @Override
  public JobConf getConfigCopy()
    {
    return new JobConf( getConfig() );
    }

  /**
   * Method setProperty sets the given key and value on the underlying properites system.
   *
   * @param key   of type String
   * @param value of type String
   */
  public void setProperty( String key, String value )
    {
    getConfig().set( key, value );
    }

  /**
   * Method getProperty returns the value associated with the given key from the underlying properties system.
   *
   * @param key of type String
   * @return String
   */
  public String getProperty( String key )
    {
    return getConfig().get( key );
    }

  @Override
  public FlowProcess getFlowProcess()
    {
    return new HadoopFlowProcess( getConfig() );
    }

  /**
   * Method isPreserveTemporaryFiles returns false if temporary files will be cleaned when this Flow completes.
   *
   * @return the preserveTemporaryFiles (type boolean) of this Flow object.
   */
  public boolean isPreserveTemporaryFiles()
    {
    return preserveTemporaryFiles;
    }

  @Override
  protected void internalStart()
    {
    try
      {
      deleteSinksIfReplace();
      }
    catch( IOException exception )
      {
      throw new FlowException( "unable to delete sinks", exception );
      }

    registerShutdownHook();
    }

  @Override
  public boolean stepsAreLocal()
    {
    return getConfig().get( "mapred.job.tracker" ).equalsIgnoreCase( "local" );
    }

  private void cleanTemporaryFiles( boolean stop )
    {
    if( stop ) // unstable to call fs operations during shutdown
      return;

    for( FlowStep step : getSteps() )
      step.clean( getConfig() );
    }

  private void registerShutdownHook()
    {
    if( !isStopJobsOnExit() )
      return;

    getHdfsShutdownHook();

    shutdownHook = new Thread()
    {
    @Override
    public void run()
      {
      HadoopFlow.this.stop();

      HadoopFlow.callHdfsShutdownHook();
      }
    };

    Runtime.getRuntime().addShutdownHook( shutdownHook );
    }

  private synchronized static void callHdfsShutdownHook()
    {
    if( --shutdownCount != 0 )
      return;

    if( hdfsShutdown != null )
      hdfsShutdown.start();
    }

  private synchronized static void getHdfsShutdownHook()
    {
    shutdownCount++;

    if( hdfsShutdown == null )
      hdfsShutdown = HadoopUtil.getHDFSShutdownHook();
    }

  private void deregisterShutdownHook()
    {
    if( !isStopJobsOnExit() || stop )
      return;

    Runtime.getRuntime().removeShutdownHook( shutdownHook );
    }

  protected void internalClean( boolean force )
    {
    if( !isPreserveTemporaryFiles() )
      cleanTemporaryFiles( force ); // force cleanup
    }

  protected void internalShutdown()
    {
    deregisterShutdownHook();
    }

  protected boolean allowParallelExecution()
    {
    return stepsAreLocal();
    }
  }