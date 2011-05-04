/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2010, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.lists.PeList;
import org.cloudbus.cloudsim.provisioners.BwProvisioner;
import org.cloudbus.cloudsim.provisioners.RamProvisioner;

/**
 * The Class HostDynamicWorkload.
 *
 * @author		Anton Beloglazov
 * @since		CloudSim Toolkit 2.0
 */
public class HostDynamicWorkload extends Host {

	/** The utilization mips. */
	private double utilizationMips;

	/** The previous utilization mips. */
	private double previousUtilizationMips;

	/** The under allocated mips. */
	private Map<String, List<List<Double>>> underAllocatedMips;

	/**
	 * Instantiates a new host.
	 *
	 * @param id the id
	 * @param ramProvisioner the ram provisioner
	 * @param bwProvisioner the bw provisioner
	 * @param storage the storage
	 * @param peList the pe list
	 * @param vmScheduler the VM scheduler
	 */
	public HostDynamicWorkload(
			int id,
			RamProvisioner ramProvisioner,
			BwProvisioner bwProvisioner,
			long storage,
			List<? extends Pe> peList,
			VmScheduler vmScheduler) {
		super(id, ramProvisioner, bwProvisioner, storage, peList, vmScheduler);
		setUtilizationMips(0);
		setPreviousUtilizationMips(0);
		setUnderAllocatedMips(new HashMap<String, List<List<Double>>>());
		setVmsMigratingIn(new ArrayList<Vm>());
	}

	/* (non-Javadoc)
	 * @see cloudsim.Host#updateVmsProcessing(double)
	 */
	@Override
	public double updateVmsProcessing(double currentTime) {
		double smallerTime = super.updateVmsProcessing(currentTime);
		setPreviousUtilizationMips(getUtilizationMips());
		setUtilizationMips(0);

		for (Vm vm : getVmList()) {
			getVmScheduler().deallocatePesForVm(vm);
		}

		for (Vm vm : getVmList()) {
			getVmScheduler().allocatePesForVm(vm, vm.getCurrentRequestedMips());
		}

		for (Vm vm : getVmList()) {
			double totalRequestedMips = vm.getCurrentRequestedTotalMips();

			if (totalRequestedMips == 0) {
				Log.printLine("VM #" + vm.getId() + " has completed its execution and destroyed");
				continue;
			}

			double totalAllocatedMips = getVmScheduler().getTotalAllocatedMipsForVm(vm);

			if (!Log.isDisabled()) {
				Log.formatLine("%.2f: [Host #" + getId() + "] Total allocated MIPS for VM #" + vm.getId() + " (Host #" + vm.getHost().getId() + ") is %.2f, was requested %.2f out of total %.2f (%.2f%%)", CloudSim.clock(), totalAllocatedMips, totalRequestedMips, vm.getMips(), totalRequestedMips / vm.getMips() * 100);

				List<Pe> pes = getVmScheduler().getPesAllocatedForVM(vm);
				StringBuilder pesString = new StringBuilder();
				for (Pe pe : pes) {
					pesString.append(String.format(" PE #" + pe.getId() + ": %.2f.", pe.getPeProvisioner().getTotalAllocatedMipsForVm(vm)));
				}
				Log.formatLine("%.2f: [Host #" + getId() + "] MIPS for VM #" + vm.getId() + " by PEs (" + getPesNumber() + " * " + getVmScheduler().getPeCapacity() + ")." + pesString, CloudSim.clock());
			}

			if (getVmsMigratingIn().contains(vm)) {
				Log.formatLine("%.2f: [Host #" + getId() + "] VM #" + vm.getId() + " is being migrated to Host #" + getId(), CloudSim.clock());
			} else {
				if (totalAllocatedMips + 0.1 < totalRequestedMips) {
					Log.formatLine("%.2f: [Host #" + getId() + "] Under allocated MIPS for VM #" + vm.getId() + ": %.2f", CloudSim.clock(), totalRequestedMips - totalAllocatedMips);
				}

				updateUnderAllocatedMips(vm, totalRequestedMips, totalAllocatedMips);

				if (vm.isInMigration()) {
					Log.formatLine("%.2f: [Host #" + getId() + "] VM #" + vm.getId() + " is in migration", CloudSim.clock());
					totalAllocatedMips /= 0.9; // performance degradation due to migration - 10%
				}
			}

			setUtilizationMips(getUtilizationMips() + totalAllocatedMips);
		}

		return smallerTime;
	}

	/**
	 * Gets the completed vms.
	 *
	 * @return the completed vms
	 */
	public List<Vm> getCompletedVms() {
		List<Vm> vmsToRemove = new ArrayList<Vm>();
		for (Vm vm : getVmList()) {
			if (vm.isInMigration()) {
				continue;
			}
			if (vm.getCurrentRequestedTotalMips() == 0) {
				vmsToRemove.add(vm);
			}
		}
		return vmsToRemove;
	}

	/**
	 * Gets the max utilization among by all PEs.
	 *
	 * @return the utilization
	 */
	public double getMaxUtilization() {
		return PeList.getMaxUtilization(getPeList());
	}

	/**
	 * Gets the max utilization among by all PEs
	 * allocated to the VM.
	 *
	 * @param vm the vm
	 *
	 * @return the utilization
	 */
	public double getMaxUtilizationAmongVmsPes(Vm vm) {
		return PeList.getMaxUtilizationAmongVmsPes(getPeList(), vm);
	}

	/**
	 * Gets the utilization of memory.
	 *
	 * @return the utilization of memory
	 */
	public double getUtilizationOfRam() {
		return getRamProvisioner().getUsedRam();
	}

	/**
	 * Gets the utilization of bw.
	 *
	 * @return the utilization of bw
	 */
	public double getUtilizationOfBw() {
		return getBwProvisioner().getUsedBw();
	}

	/**
	 * Update under allocated mips.
	 *
	 * @param vm the vm
	 * @param requested the requested
	 * @param allocated the allocated
	 */
	protected void updateUnderAllocatedMips(Vm vm, double requested, double allocated) {
		List<List<Double>> underAllocatedMipsArray;
		List<Double> underAllocatedMips = new ArrayList<Double>();
		underAllocatedMips.add(requested);
		underAllocatedMips.add(allocated);

		if (getUnderAllocatedMips().containsKey(vm.getUid())) {
			underAllocatedMipsArray = getUnderAllocatedMips().get(vm.getUid());
		} else {
			underAllocatedMipsArray = new ArrayList<List<Double>>();
		}

		underAllocatedMipsArray.add(underAllocatedMips);
		getUnderAllocatedMips().put(vm.getUid(), underAllocatedMipsArray);
	}

	/**
	 * Get current utilization of CPU in percentage.
	 *
	 * @return current utilization of CPU in percents
	 */
	public double getUtilizationOfCpu() {
		double utilization = getUtilizationMips() / getTotalMips();
		if (utilization > 1 && utilization < 1.01) {
			utilization = 1;
		}
		return utilization;
	}

	/**
	 * Gets the previous utilization of CPU in percentage.
	 *
	 * @return the previous utilization of cpu
	 */
	public double getPreviousUtilizationOfCpu() {
		double utilization = getPreviousUtilizationMips() / getTotalMips();
		if (utilization > 1 && utilization < 1.01) {
			utilization = 1;
		}
		return utilization;
	}

	/**
	 * Get current utilization of CPU in MIPS.
	 *
	 * @return current utilization of CPU in MIPS
	 */
	public double getUtilizationOfCpuMips() {
		return getUtilizationMips();
	}

	/**
	 * Gets the utilization mips.
	 *
	 * @return the utilization mips
	 */
	public double getUtilizationMips() {
		return utilizationMips;
	}

	/**
	 * Sets the utilization mips.
	 *
	 * @param utilizationMips the new utilization mips
	 */
	protected void setUtilizationMips(double utilizationMips) {
		this.utilizationMips = utilizationMips;
	}

	/**
	 * Gets the previous utilization mips.
	 *
	 * @return the previous utilization mips
	 */
	public double getPreviousUtilizationMips() {
		return previousUtilizationMips;
	}

	/**
	 * Sets the previous utilization mips.
	 *
	 * @param previousUtilizationMips the new previous utilization mips
	 */
	protected void setPreviousUtilizationMips(double previousUtilizationMips) {
		this.previousUtilizationMips = previousUtilizationMips;
	}

    /**
     * Gets the under allocated mips.
     *
     * @return the under allocated mips
     */
    public Map<String, List<List<Double>>> getUnderAllocatedMips() {
		return underAllocatedMips;
	}

	/**
	 * Sets the under allocated mips.
	 *
	 * @param underAllocatedMips the under allocated mips
	 */
	protected void setUnderAllocatedMips(Map<String, List<List<Double>>> underAllocatedMips) {
		this.underAllocatedMips = underAllocatedMips;
	}

}
