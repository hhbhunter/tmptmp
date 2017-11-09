package com.stp.distribution.client;
/**
 * 
 * @author hhbhunter
 *
 */
import java.io.File;
import java.util.Map;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.utils.ZKPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;
import com.stp.distribution.entity.ProcessKey;
import com.stp.distribution.entity.TaskType;
import com.stp.distribution.entity.ZkTask;
import com.stp.distribution.entity.ZkTaskStatus;
import com.stp.distribution.framwork.ZKConfig;
import com.stp.distribution.framwork.ZkDataUtils;
import com.stp.distribution.framwork.ZkException;
import com.stp.distribution.framwork.ZkTaskPath;
import com.stp.distribution.user.TaskCache;
import com.stp.distribution.util.CmdExec;
import com.stp.distribution.util.UtilTool;

public class ZkClientTask {
	public static Map<Integer,CmdExec> exeMap=Maps.newConcurrentMap();
	private static final Logger clientLOG = LoggerFactory.getLogger(ZkClientTask.class);
	CuratorFramework zkInstance;
	public ZkClientTask(CuratorFramework zkInstance){
		this.zkInstance=zkInstance;
	}

	public void taskProcess(PathChildrenCacheEvent event,Map<Integer,ZkTask> map,String myip) throws Exception{
		ZkTask task = new ZkTask();
		String dataPath = null;
		try {
			dataPath=event.getData().getPath();
			clientLOG.debug("datapath="+dataPath);
			String taskjson=new String(event.getData().getData(),ZKConfig.getZkCharset());
			clientLOG.debug("taskjson==="+taskjson);
			task = TaskCache.json2zktask(taskjson);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		switch (event.getType()) {

		case CHILD_ADDED:
			if(!ZkDataUtils.isExists(dataPath)){
				throw new ZkException(dataPath+"is not exist !!!");
			}
			clientLOG.info("client CHILD_ADDED data ==== "+event.getData().getPath());
			updateMonitorNum(1, task.getType(),myip);
			map.put(task.getTaskid(), task);
			clientZkTaskEvent(task);
			break;
		case CHILD_REMOVED:
			updateMonitorNum(-1, task.getType(),myip);
			map.remove(task.getTaskid());
			clientLOG.info("client CHILD_REMOVED data:"+event.getData().getPath());
			break;
		case CHILD_UPDATED:
			//任务状态变更
			clientZkTaskEvent(task);
			clientLOG.info("client CHILD_UPDATED data:"+event.getData().getPath()+ " stat:"+task.getStat());
			break;
		default:

			break;
		}
	}
	/**
	 * client task process operator
	 * @param task
	 * @throws Exception
	 */
	private void clientZkTaskEvent(ZkTask task) throws Exception {
		String myip=UtilTool.getLocalIp();
		String myProcessStatPath=ZKPaths.makePath(ZkTaskPath.getProcessPath(task.getType()), String.valueOf(task.getTaskid()));
		clientLOG.info("myProcessStatPath="+myProcessStatPath+" stat="+task.getStat());

		if(!ZkDataUtils.isExists(myProcessStatPath)){
			clientLOG.error(myProcessStatPath+" is not exists !!!");
			return;
		}	
		String myClientProcessPath=ZKPaths.makePath(myProcessStatPath, myip);


		switch (ZkTaskStatus.valueOf(task.getStat())) {
		case success:
			//process task update
			ZkDataUtils.setKVData(myClientProcessPath, ProcessKey.STAT, ZkTaskStatus.success.name());

			break;
		case fail:
			//process rewrite stat
			ZkDataUtils.setKVData(myClientProcessPath, ProcessKey.STAT, ZkTaskStatus.fail.name());

			break;
		case start:
			//client task stat update
			//执行命令
			ZkDataUtils.setKVData(myClientProcessPath, ProcessKey.STAT, ZkTaskStatus.running.name());
			new TaskExceute(task).start();

			break;
		case stop:
			new TaskExceute(task).start();
			break;
		case pause:
			//					ZkDataUtils.setKVData(myClientProcessPath, ProcessKey.STAT, ZkTaskStatus.pause.name());
			break;
		case pending:
			ZkDataUtils.setKVData(myClientProcessPath, ProcessKey.STAT, ZkTaskStatus.pending.name());
			break;
		case finish:
			//client task del

			ZkDataUtils.setKVData(myClientProcessPath, ProcessKey.STAT, ZkTaskStatus.finish.name());
			String myTaskPath=ZKPaths.makePath(ZkTaskPath.getClientTaskPath(task.getType(), myip), String.valueOf(task.getTaskid()));
			if(ZkDataUtils.isExists(myTaskPath)){
				ZkDataUtils.removeDataPath(myTaskPath);
				clientLOG.info("remove the path="+myTaskPath);
			}
			break;
		default:
			break;
		}

	}

	public synchronized void updateMonitorNum(int num,String type,String myip) throws Exception{
		//		String clientPath=ZkTaskPath.getClientTaskPath(type, myip);
		String registPath=ZKPaths.makePath(ZkTaskPath.getMonitorPath(type), myip);
		String confPath=ZkTaskPath.getClientTaskPath(type, myip);
		//		System.out.println("confpath count="+ZkDataUtils.getData(confPath));
		String exenum=ZkDataUtils.getData(registPath);
		System.out.println("current exeNum == "+exenum);
		exenum=String.valueOf(Integer.valueOf(exenum)+num);
		System.out.println("update exeNum == "+exenum);
		ZkDataUtils.setData(registPath, exenum);
		ZkDataUtils.setData(confPath, exenum);
	}
	/**
	 * process更新任务状态，通知各个client
	 * 只包含start、stop、finish
	 * @author houhuibin
	 *
	 */

	private class TaskExceute extends Thread{
		ZkTask task;
		CmdExec exe =new CmdExec();
		public TaskExceute(ZkTask task) {
			this.task=task;
		}
		@Override
		public void run() {
			switch (ZkTaskStatus.valueOf(task.getStat())) {
			case stop:
				stopTask();
				break;
			case start:
				startTask();
				break;

			default:
				break;
			}
			try {
				clientZkTaskEvent(task);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

		}

		public void startTask(){

			clientLOG.info("Client start execute cmd="+task.getStartCmd()+" cmdPath="+task.getCmdPath());
			try {
				exeMap.put(task.getTaskid(), exe);
				//				Thread.sleep(10000);
				int stat=exe.cmdExec(task.getStartCmd(),null,new File(task.getCmdPath()),true);
				if(stat==0){
					task.setStat(ZkTaskStatus.success.name());
					
				}else{
					task.setStat(ZkTaskStatus.fail.name());

				}

			} catch (Exception e) {
				// TODO Auto-generated catch block
				task.setStat(ZkTaskStatus.fail.name());
				task.setLog(e.getMessage());
				e.printStackTrace();
			}
		}

		public void stopTask() {
			if(!exeMap.isEmpty()){

				CmdExec exe=exeMap.get(task.getTaskid());
				if(exe!=null)
					exe.stop();
			}
			
			switch (TaskType.valueOf(task.getType())) {
			case AUTO:
				task.setStat(ZkTaskStatus.finish.name());
				break;
			case PERFORME:
				if(!new File(task.getCmdPath()).exists()){
					task.setLog(task.getCmdPath()+" is not exist !!");
					task.setStat(ZkTaskStatus.finish.name());
					break;
				}
				if(!new File(task.getCmdPath()+File.separator+task.getTaskid()+".pid").exists()){
					task.setLog(task.getCmdPath()+File.separator+task.getTaskid()+".pid"+" is not exist !!");
					task.setStat(ZkTaskStatus.finish.name());
					break;
				}
				try {
					int stat=exe.cmdExec(task.getStopCmd(),null,new File(task.getCmdPath()),true);
					if(stat==0){
						clientLOG.info("PERFORME task id "+task.getTaskid()+" stop ok !!");
						task.setStat(ZkTaskStatus.finish.name());

					}else{
						//if fail we should do something ,ex: record this and deal
						task.setStat(ZkTaskStatus.fail.name());
						clientLOG.info("PERFORME task id "+task.getTaskid()+" stop failed !!");
					}

				} catch (Exception e) {
					task.setStat(ZkTaskStatus.fail.name());
					clientLOG.info("PERFORME task id "+task.getTaskid()+" stop failed !!");
					task.setLog(e.getMessage());
					e.printStackTrace();
				}

				break;

			default:
				break;
			}
		}

	}


}
