
package CRC_Prediction;


import java.util.Map;
import org.apache.commons.math3.util.Pair;
import org.jblas.DoubleMatrix;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.collect.Multimap;
import CRC_Prediction.Utils.MatrixUtilityJBLAS;


/**
 * Create LikelihoodFunction class object and set its parameters
 * 
 * @author John Kalantari
 * Copyright 2019, Mayo Foundation for Medical Education and Research
 *
 */
public class LikelihoodFunctionCancer
{
	
	public double			_eta;
	public double			_slackPenalty;
	public boolean			_useNaturalGradient;
	
//	private static Cluster	_cluster;	// GTD Not used
	private static Session	_session;
	
	
	/**
	 * Constructor for Likelihood function for Bayesian IRL or Maximum Likelihood IRL
	 * 
	 * @param e
	 */
	public LikelihoodFunctionCancer (double e)
	{
		setEta (e);
	}
	
	
	public LikelihoodFunctionCancer (double e, Cluster cassCluster, Session cassSession)
	{
		setEta (e);
//		_cluster = cassCluster;	// GTD Not used
		_session = cassSession;
	}
	
	
	public LikelihoodFunctionCancer ()
	{
		// Use MaxEnt IRL
	}
	
	
	public void setEta (double e)
	{
		_eta = e;
	}
	
	
	public double getEta ()
	{
		return _eta;
	}
	
	
	/**
	 * JK data validated 7.25.2019
	 * Compute log LIKELIHOOD and GRADIENT of trajectories given reward function w and inverse
	 * temperature eta, log p(X | w, opts.eta)
	 * with Bayesian IRL method
	 * 
	 * @param env
	 * @param irlalgo
	 * @param weightMatr
	 * @param stateActionPairCountsInfoForSubsetOfTrajs
	 * @param policyMatrix
	 * @param Hmatrix
	 * @param qMatrixGrad
	 * @param computeGradient
	 * @return Pair<Double, double[][]> logLikelihoodAndRewardGradient
	 */
	protected Pair<Double, DoubleMatrix> computeLogLikelihoodAndGradient_Bayesian (MDPCancer env, IRLAlgorithmCancer irlalgo, double[][] weightMatr, 
																				Multimap<Integer, double[]> stateActionPairCountsInfoForSubsetOfTrajs, 
																					double[][] policyMatrix, double[][] Hmatrix, 
																					double[][] qMatrixGrad, boolean computeGradient)
	{
		int numFeatures = env.getNumRewardFeatures ();
		int numStates = env.getNumStates ();
		int numActions = env.getNumActions ();
		
		double[][] qMatrixGradient = qMatrixGrad;
		Double likelihood = 0.0;
		double[][] qMatrix_Optimal;
		DoubleMatrix rewardGradient = null;
		
		RewardFunctionGenerationCancer.generateWeightedRewardFunction (env, weightMatr);
		if ((policyMatrix == null) || (Hmatrix == null))
		{

			// this call to policyIteration needs policy to be null no matter what.
			Map<String, double[][]> policy_Value_H_Q_Matrices = PolicySolverCancer.runPolicyIteration (env, irlalgo, null); 
			
			if (computeGradient && (qMatrixGradient == null))
			{
				qMatrixGradient = InferenceAlgoCancer.computeQMatrixGradient (policy_Value_H_Q_Matrices.get ("P"), env);
			}
			qMatrix_Optimal = policy_Value_H_Q_Matrices.get ("Q");
			
		}
		else
		{ /// NOTE: This else() case will NEVER be called by CRP-IRL algorithm. This exists in case
			/// an alternative IRL algorithm is to be used (such as Expectation Maximization algo)
			/// that allows wishes to use a Bayesian IRL based method for computing its LLH.
			double[][] valueMatrixLikeLihood = new DoubleMatrix (Hmatrix).mmul (new DoubleMatrix (weightMatr)).toArray2 ();
			Map<String, double[][]> optimalQVPmap =  PolicySolverCancer.policyImprovementStep (env, valueMatrixLikeLihood, null);
			double[][] optimalQMatrix = optimalQVPmap.get("Q");

			qMatrix_Optimal = optimalQMatrix;
		}
		
		// Compute Likelihood JK 6.22.2019
		double[][] policySM1 = new DoubleMatrix (qMatrix_Optimal).mul (_eta).toArray2 ();
		double[][] policySM = MatrixUtilityJBLAS.exp (policySM1);
		double[][] sum_policySM = MatrixUtilityJBLAS.sumPerRow (policySM);
		double[][] log_sumPolicySM = MatrixUtilityJBLAS.log (sum_policySM);
		DoubleMatrix log_sumPolicySMDBLMatrix = new DoubleMatrix (log_sumPolicySM);
		DoubleMatrix policySM1DBLMatrix = new DoubleMatrix (policySM1);
		

		DoubleMatrix qBasedLikelihood = MatrixUtilityJBLAS.elementwiseSubtractionByColumnVector (policySM1DBLMatrix, log_sumPolicySMDBLMatrix);
		
		for (double[] observedSAPair_i : stateActionPairCountsInfoForSubsetOfTrajs.values ())
		{
			Double state_i = observedSAPair_i[0];
			Double action_i = observedSAPair_i[1];
			Double count_i = observedSAPair_i[2];
			likelihood = likelihood + qBasedLikelihood.get (state_i.intValue (), action_i.intValue ()) * count_i;
		}
		
		if (!computeGradient)
		{
			rewardGradient = null;
		}
		else
		{ // else if boolean indicates that gradient of reward function needs to be computed
			
			// compute soft-max policy
			double[][] exppolicySM1 = MatrixUtilityJBLAS.exp (policySM1);
			double[][] sum_exppolicySM1 = MatrixUtilityJBLAS.sumPerRow (exppolicySM1);
			DoubleMatrix exppolicySM1DBLMat = new DoubleMatrix (exppolicySM1);
			DoubleMatrix sum_exppolicySM1DBLMat = new DoubleMatrix (sum_exppolicySM1);
			
			DoubleMatrix policyDivDBLMatrix = MatrixUtilityJBLAS.elementwiseDivisionByColumnVector (exppolicySM1DBLMat, sum_exppolicySM1DBLMat);
			
			// Compute gradient of policy with respect to the weightMatrix which is based on the numFeatures)
			DoubleMatrix logPolicyGradientWRTfeatures = new DoubleMatrix (numFeatures, numStates * numActions);
			
			DoubleMatrix xMatr = new DoubleMatrix (numStates, numActions);
			for (int f = 0; f < numFeatures; f++)
			{
				// need to convert row vector for feature f in dQ into [numStates x numActions] matrix
				double[] featureRowVector = qMatrixGradient[f]; // [1 x (numStates*numActions)] matrix
				DoubleMatrix fRowMatrix = new DoubleMatrix (featureRowVector);
				
				xMatr = fRowMatrix.reshape(numStates, numActions);
				DoubleMatrix yMatrRowSumVec = MatrixUtilityJBLAS.elementwiseMultiply(policyDivDBLMatrix,xMatr).rowSums();
				
				DoubleMatrix zMatr = MatrixUtilityJBLAS.elementwiseSubtractionByColumnVector (xMatr, yMatrRowSumVec).mul (_eta);
				
				DoubleMatrix zFeatureRowMatrix = zMatr.reshape (1, numStates * numActions);
				
				logPolicyGradientWRTfeatures.putRow (f, zFeatureRowMatrix);
			}
			
			// Compute GRADIENT of reward function (rewardGradient) using policyGradient and s-a pair counts
			for (double[] observedSAPair_i : stateActionPairCountsInfoForSubsetOfTrajs.values ())
			{
				Double state_i = observedSAPair_i[0];
				Double action_i = observedSAPair_i[1];
				Double count_i = observedSAPair_i[2];
				// Double columnSubScriptDblVal = (action_i-1.0)*numStates+state_i; //this formula
				// would work if our actions start at 1 and states start at 1
				Double columnSubScriptDblVal = (action_i) * numStates + state_i; // get subscript of column of interest
				int columnSubScriptIntVal = columnSubScriptDblVal.intValue ();
				if (rewardGradient == null)
				{


					rewardGradient = logPolicyGradientWRTfeatures.getColumn (columnSubScriptIntVal).mul (count_i);
				}
				else
				{
					rewardGradient = rewardGradient.add (logPolicyGradientWRTfeatures.getColumn (columnSubScriptIntVal).mul (count_i));
				}
			} // end for-loop computing reward function gradient
			
			//JK 7.19.2019 Checking if rewardGradient calculation  is NULL
			if(rewardGradient == null) {
				System.err.println("rewardGradient is NULL!!!");
			}
			
		}
		
		Pair<Double, DoubleMatrix> logLikelihoodAndRewardGradient = new Pair<Double, DoubleMatrix> (likelihood, rewardGradient);
		
		return logLikelihoodAndRewardGradient;
	}
	
	
	/**
	 * 
	 * Compute log LIKELIHOOD and GRADIENT of trajectories given reward function w and inverse
	 * temperature eta, log p(X | w, opts.eta)
	 * with Bayesian IRL method using Cassandra db table containing counts for each state-action
	 * pair found in the set of trajectories of interest
	 * 
	 * @param env
	 * @param irlalgo
	 * @param weightMatr
	 * @param policyMatrix
	 * @param Hmatrix
	 * @param qMatrixGrad
	 * @param computeGradient
	 * @return Pair<Double, double[][]> logLikelihoodAndRewardGradient
	 */
	protected Pair<Double, DoubleMatrix> computeLogLikelihoodAndGradient_BayesianWithDatabase (
			MDPCancer env, IRLAlgorithmCancer irlalgo, double[][] weightMatr,
			double[][] policyMatrix, double[][] Hmatrix, double[][] qMatrixGrad,
			boolean computeGradient)
	{
		
		int numFeatures = env.getNumRewardFeatures ();
		int numStates = env.getNumStates ();
		int numActions = env.getNumActions ();
		
		double[][] qMatrixGradient = qMatrixGrad;
		Double likelihood = 0.0;
		double[][] qMatrix_Optimal;
		DoubleMatrix rewardGradient = null;
		
		Double state_i = null;
		Double action_i = null;
		Double count_i = null;
		
		RewardFunctionGenerationCancer.generateWeightedRewardFunction (env, weightMatr);
		if ((policyMatrix == null) || (Hmatrix == null))
		{
			Map<String, double[][]> policy_Value_H_Q_Matrices = PolicySolverCancer.runPolicyIteration (env, irlalgo, null);
			// this call to policyIteration needs policy to be null no matter what.
			
			if (computeGradient && qMatrixGradient == null)
			{
				qMatrixGradient = InferenceAlgoCancer.computeQMatrixGradient (policy_Value_H_Q_Matrices.get ("P"), env);
			}
			qMatrix_Optimal = policy_Value_H_Q_Matrices.get ("Q");
			
		}
		else
		{	/// NOTE: This else() case will NEVER be called by CRP-IRL algorithm. This exists in case
			/// an alternative IRL algorithm is to be used (such as Expectation Maximization algo)
			/// that allows wishes to use a Bayesian IRL based method for computing its LLH.
			double[][] valueMatrixLikeLihood = new DoubleMatrix (Hmatrix).mmul (new DoubleMatrix (weightMatr)).toArray2 ();
			Map<String, double[][]> optimalQVPmap = PolicySolverCancer.policyImprovementStep (env, valueMatrixLikeLihood, null);
			double[][] optimalQMatrix = optimalQVPmap.get("Q");

			qMatrix_Optimal = optimalQMatrix;
		}
		
		// Compute Likelihood JK 6.22.2019
		double[][] policySM1 = new DoubleMatrix (qMatrix_Optimal).mul (_eta).toArray2 ();
		double[][] sum_policySM1 = MatrixUtilityJBLAS.sumPerRow (policySM1);
		DoubleMatrix sum_policySM1DBLMatrix = new DoubleMatrix (sum_policySM1);
		double[][] policySM = MatrixUtilityJBLAS.exp (policySM1);
		double[][] sum_policySM = MatrixUtilityJBLAS.sumPerRow (policySM);
		double[][] log_sumPolicySM = MatrixUtilityJBLAS.log (sum_policySM);
		DoubleMatrix log_sumPolicySMDBLMatrix = new DoubleMatrix (log_sumPolicySM);
		DoubleMatrix policySM1DBLMatrix = new DoubleMatrix (policySM1);
		DoubleMatrix policyQuotient = MatrixUtilityJBLAS.elementwiseDivisionByColumnVector (policySM1DBLMatrix, sum_policySM1DBLMatrix);
		

		DoubleMatrix qBasedLikelihood = MatrixUtilityJBLAS.elementwiseSubtractionByColumnVector (policySM1DBLMatrix, log_sumPolicySMDBLMatrix);
		
		String cqlSelectPairCountInfofrom_countInfoTable = "select * from countinfofortrajs_table";
		for (Row cinfo_row : _session.execute (cqlSelectPairCountInfofrom_countInfoTable))
		{
			state_i = cinfo_row.getDouble ("statedbl");
			action_i = cinfo_row.getDouble ("actiondbl");
			count_i = cinfo_row.getDouble ("countdbl");
			likelihood = likelihood + qBasedLikelihood.get (state_i.intValue (), action_i.intValue ()) * count_i;
		}
		
		if (!computeGradient)
		{
			rewardGradient = null;
		}
		else
		{ // else if boolean indicates that gradient of reward function needs to be computed
			
			// compute soft-max policy
			
			// Compute gradient of policy with respect to the weightMatrix which is based on the numFeatures)
			DoubleMatrix logPolicyGradientWRTfeatures = new DoubleMatrix (numFeatures, numStates * numActions);
			
			DoubleMatrix xMatr = new DoubleMatrix (numStates, numActions);
			for (int f = 0; f < numFeatures; f++)
			{
				// need to convert row vector for feature f in dQ into [numStates x numActions]
				// matrix
				double[] featureRowVector = qMatrixGradient[f]; // [1 x (numStates*numActions)] matrix
				DoubleMatrix fRowMatrix = new DoubleMatrix (featureRowVector);

				xMatr = fRowMatrix.reshape (numStates, numActions);
				DoubleMatrix yMatrRowSumVec = MatrixUtilityJBLAS.elementwiseMultiply (policyQuotient, xMatr).rowSums ();
				
				
				DoubleMatrix zMatr = MatrixUtilityJBLAS.elementwiseSubtractionByColumnVector (xMatr, yMatrRowSumVec).mul (_eta);
				
				DoubleMatrix zFeatureRowMatrix = zMatr.reshape (1, numStates * numActions);
				
				logPolicyGradientWRTfeatures.putRow (f, zFeatureRowMatrix);
			}
			
			// Compute GRADIENT of reward function (rewardGradient) using policyGradient and s-a
			// pair counts
			
			cqlSelectPairCountInfofrom_countInfoTable = "select * from countinfofortrajs_table";
			for (Row cinfo_row : _session.execute (cqlSelectPairCountInfofrom_countInfoTable))
			{
				state_i = cinfo_row.getDouble ("statedbl");
				action_i = cinfo_row.getDouble ("actiondbl");
				count_i = cinfo_row.getDouble ("countdbl");
				
				Double columnSubScriptDblVal = (action_i) * numStates + state_i; // get subscript of column of interest
				int columnSubScriptIntVal = columnSubScriptDblVal.intValue ();
				if (rewardGradient == null)
				{
					rewardGradient = logPolicyGradientWRTfeatures.getColumn (columnSubScriptIntVal).mul (count_i);
				}
				else
				{
					rewardGradient = rewardGradient.add (logPolicyGradientWRTfeatures.getColumn (columnSubScriptIntVal).mul (count_i));
				}
			} // end for-loop computing reward function gradient
			
		}
		
		Pair<Double, DoubleMatrix> logLikelihoodAndRewardGradient = new Pair<Double, DoubleMatrix> (likelihood, rewardGradient);
		
		return logLikelihoodAndRewardGradient;
	}
	
	

	
}
