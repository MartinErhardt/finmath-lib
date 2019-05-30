package net.finmath.singleswaprate.calibration;


import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;

import org.junit.Assert;
import org.junit.Test;

import net.finmath.marketdata.model.curves.DiscountCurve;
import net.finmath.marketdata.model.curves.ForwardCurveFromDiscountCurve;
import net.finmath.marketdata.model.curves.ForwardCurve;
import net.finmath.marketdata.model.volatilities.SwaptionDataLattice;
import net.finmath.marketdata.products.Swap;
import net.finmath.optimizer.SolverException;
import net.finmath.singleswaprate.annuitymapping.AnnuityMapping.AnnuityMappingType;
import net.finmath.singleswaprate.data.DataTable;
import net.finmath.singleswaprate.data.DataTableLight;
import net.finmath.singleswaprate.model.AnalyticModelWithVolatilityCubes;
import net.finmath.singleswaprate.model.VolatilityCubeModel;
import net.finmath.singleswaprate.model.volatilities.VolatilityCube;
import net.finmath.singleswaprate.products.CashSettledPayerSwaption;
import net.finmath.singleswaprate.products.CashSettledReceiverSwaption;
import net.finmath.time.Schedule;
import net.finmath.time.SchedulePrototype;
import net.finmath.time.businessdaycalendar.BusinessdayCalendar;
import net.finmath.time.businessdaycalendar.BusinessdayCalendarExcludingTARGETHolidays;

public class SABRCubeCalibrationTEST {

	private double testAccuracy = 0.01;
	private int calibrationMaxIteration = 5;
	private static boolean useLinearInterpolation	= true;

	private boolean replicationUseAsOffset = true;
	private double replicationLowerBound   = -0.15;
	private double replicationUpperBound   = 0.15;
	private int replicationNumberOfEvaluationPoints = 50;

	// files
	private String curveFilePath				= "./src/test/resources/curves";
	private String discountCurveFileName		= "EUR-EONIA.crv";
	private String forwardCurveFileName			= "EUR-OIS6M.crv";
	private String swaptionFilePath				= "./src/test/resources/swaptions";
	private String payerFileName				= "CashPayerSwaptionPrice.sdl";
	private String receiverFileName				= "CashReceiverSwaptionPrice.sdl";
	private String physicalFileName				= "PhysicalSwaptionPriceATM.sdl";

	private AnnuityMappingType type = AnnuityMappingType.MULTIPITERBARG;

	// cube parameters
	private double displacement = 0.25;
	private double beta = 0.5;
	private double correlationDecay = 0.045;
	private double iborOisDecorrelation = 1.2;
	private LocalDate referenceDate = LocalDate.of(2017, 8, 30);

	//schedule data
	private SchedulePrototype floatMetaSchedule;
	private SchedulePrototype fixMetaSchedule;


	private VolatilityCubeModel model;
	private String discountCurveName;
	private String forwardCurveName;
	private VolatilityCube cube;
	private SwaptionDataLattice payerSwaptions;
	private SwaptionDataLattice receiverSwaptions;
	private SwaptionDataLattice physicalSwaptions;

	private boolean useCustomInitialParameters = false;
	private DataTable initialRhos;
	private DataTable initialBaseVols;
	private DataTable initialVolvols;

	public SABRCubeCalibrationTEST() {

		//Get curves
		DiscountCurve discountCurve = null;
		DiscountCurve forwardDiscountCurve = null;
		ForwardCurve forwardCurve = null;
		try (ObjectInputStream discountIn = new ObjectInputStream(new FileInputStream(new File(curveFilePath, discountCurveFileName)));
				ObjectInputStream forwardIn = new ObjectInputStream(new FileInputStream(new File(curveFilePath, forwardCurveFileName)))) {
			discountCurve = (DiscountCurve) discountIn.readObject();
			forwardDiscountCurve = (DiscountCurve) forwardIn.readObject();
		} catch (ClassNotFoundException | IOException e) {
			e.printStackTrace();
		}
		forwardCurve = new ForwardCurveFromDiscountCurve("Forward-" + forwardDiscountCurve.getName(), forwardDiscountCurve.getName(), discountCurve.getName(), referenceDate, "6M",
				new BusinessdayCalendarExcludingTARGETHolidays(), BusinessdayCalendar.DateRollConvention.FOLLOWING, 365/360.0, 0);

		model = new AnalyticModelWithVolatilityCubes();
		model = (AnalyticModelWithVolatilityCubes) model.addCurve(discountCurve.getName(), discountCurve);
		model = (AnalyticModelWithVolatilityCubes) model.addCurve(forwardCurve.getName(), forwardCurve);
		model = (AnalyticModelWithVolatilityCubes) model.addCurve(forwardDiscountCurve.getName(), forwardDiscountCurve);

		discountCurveName	= discountCurve.getName();
		forwardCurveName	= forwardCurve.getName();

		//Get swaption data
		try (ObjectInputStream inPayer = new ObjectInputStream(new FileInputStream(new File(swaptionFilePath, payerFileName)));
				ObjectInputStream inReceiver = new ObjectInputStream(new FileInputStream(new File(swaptionFilePath, receiverFileName)));
				ObjectInputStream inPhysical = new ObjectInputStream(new FileInputStream(new File(swaptionFilePath, physicalFileName)))) {
			payerSwaptions 		= (SwaptionDataLattice) inPayer.readObject();
			receiverSwaptions	= (SwaptionDataLattice) inReceiver.readObject();
			physicalSwaptions	= (SwaptionDataLattice) inPhysical.readObject();

			fixMetaSchedule		= physicalSwaptions.getFixMetaSchedule();
			floatMetaSchedule	= physicalSwaptions.getFloatMetaSchedule();
		} catch (ClassNotFoundException | IOException e) {
			e.printStackTrace();
		}

	}

	@Test
	public void testSABRCubeCalibration() {

		System.out.println("Running calibration...");
		long startTime = System.currentTimeMillis();

		SABRCubeCalibration calibrator = new SABRCubeCalibration(referenceDate, payerSwaptions, receiverSwaptions, physicalSwaptions,
				model, type, displacement, beta, correlationDecay, iborOisDecorrelation);
		calibrator.setCalibrationParameters(calibrationMaxIteration, Runtime.getRuntime().availableProcessors());
		calibrator.setReplicationParameters(replicationUseAsOffset, replicationLowerBound, replicationUpperBound, replicationNumberOfEvaluationPoints);
		calibrator.setUseLinearInterpolation(useLinearInterpolation);
		if(useCustomInitialParameters) {
			calibrator.setInitialParameters(initialRhos, initialBaseVols, initialVolvols);
		}

		int[] terminations = physicalSwaptions.getTenors();

		try {
			cube = calibrator.calibrate("CalibratedSABRCube", terminations);
		} catch (SolverException e) {
			e.printStackTrace();
		}

		long endTime = System.currentTimeMillis();

		System.out.println("\nCalibration finished after "+(endTime-startTime)/1000 +"s.");
		System.out.println("Cube calibrated to parameters:");
		System.out.println(cube.getParameters().toString());

		System.out.println("\nValue of CSPayerSwaption\nmoneyness maturity termination | model-value market-value");
		for(int moneyness : payerSwaptions.getMoneyness()) {
			for(int maturity : payerSwaptions.getMaturities(moneyness)) {
				for(int termination : payerSwaptions.getTenors(moneyness, maturity)) {
					double valueModel	= payerValue(model.addVolatilityCube(cube), maturity, termination, moneyness);
					double valueMarket	= payerSwaptions.getValue(maturity, termination, moneyness);

					System.out.println(moneyness + "\t" + maturity + "\t" + termination + "\t|\t" + valueModel + "\t" + valueMarket);
					Assert.assertEquals(valueMarket, valueModel, testAccuracy);
				}
			}
		}

		System.out.println("\nValue of CSReceiverSwaption\nmoneyness maturity termination | model-value market-value");
		for(int moneyness : receiverSwaptions.getMoneyness()) {
			for(int maturity : receiverSwaptions.getMaturities(moneyness)) {
				for(int termination : receiverSwaptions.getTenors(moneyness, maturity)) {
					double valueModel	= receiverValue(model.addVolatilityCube(cube), maturity, termination, moneyness);
					double valueMarket	= receiverSwaptions.getValue(maturity, termination, moneyness);

					System.out.println(moneyness + "\t" + maturity + "\t" + termination + "\t|\t" + valueModel + "\t" + valueMarket);
					Assert.assertEquals(valueMarket, valueModel, testAccuracy);
				}
			}
		}
	}

	public static void main(String[] args) {

		SABRCubeCalibrationTEST test = new SABRCubeCalibrationTEST();

		if(JOptionPane.showConfirmDialog(null, "Load tables from file for custom initial calibration parameters?", "Initial Parameters",
				JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION) {
			JFileChooser jfc = new JFileChooser();
			jfc.setDialogTitle("Select file containing tables");
			jfc.setFileSelectionMode(JFileChooser.FILES_ONLY);
			jfc.setMultiSelectionEnabled(false);
			if(jfc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
				try(ObjectInputStream in = new ObjectInputStream(new FileInputStream(jfc.getSelectedFile()))) {

					Object obj = null;
					DataTable initialRhos		= null;
					DataTable initialBaseVols	= null;
					DataTable initialVolvols	= null;
					while(true) {
						try {
							obj = in.readObject();
							if(obj instanceof Map<?,?>) {
								Map<?, ?> map	= (Map<?, ?>) obj;
								@SuppressWarnings("unchecked")
								DataTable table = new DataTableLight(
										(String) map.get("name"),
										DataTable.TableConvention.valueOf((String) map.get("tableConvention")),
										(List<Integer>) map.get("maturities"),
										(List<Integer>) map.get("terminations"),
										(List<Double>) map.get("values"));
								String name = table.getName().toUpperCase();
								if(name.contains("RHO")) {
									initialRhos = table;
								} else if(name.contains("VOLVOL")) {
									initialVolvols = table;
								} else if(name.contains("BASEVOL")) {
									initialBaseVols = table;
								}
							}
						} catch (EOFException e) {
							break;
						}
					}
					if(initialRhos != null && initialBaseVols != null && initialVolvols != null) {
						test.useCustomInitialParameters	= true;
						test.initialRhos				= initialRhos;
						test.initialBaseVols			= initialBaseVols;
						test.initialVolvols				= initialVolvols;
						System.out.println("Proceeding with provided tables.");
					}
				} catch (IOException | ClassNotFoundException e) {
					System.out.println("Failed to load tables from file. Continuing with precalibration.");
				}
			}
		}

		test.testSABRCubeCalibration();
		test.askForSwaptions();
	}

	public void askForSwaptions() {
		System.out.println("Evaluate other swaptions?");
		Scanner in = new Scanner(System.in);
		String line;
		while(in.hasNextLine()) {
			line = in.nextLine();

			if(line.equals("q")) {
				in.close(); break;
			}

			String[] inputs;
			int moneyness;
			int maturity;
			int termination;

			try {
				inputs = line.split(" ");
				moneyness = Integer.parseInt(inputs[1]);
				maturity = Integer.parseInt(inputs[2]);
				termination = Integer.parseInt(inputs[3]);
			} catch (Exception e) {
				System.out.println("Usage: p/r moneyness maturity termination");
				System.out.println("Or type q to quit.");
				continue;
			}

			switch(inputs[0]) {
			case "p" :
				System.out.println("Value of CSPayerSwaption, moneyness "+moneyness+", maturity "+maturity+", termination "+termination);
				try {
					System.out.println("Model: "+ payerValue(model.addVolatilityCube(cube),maturity, termination, moneyness));
				} catch (Exception e) {
					System.out.println("Model failed to evaluate.");
					System.out.println("Print stack trace? y/n");
					while(in.hasNext())
						if(in.next().equals("y"))
						{ e.printStackTrace(); break; }
						else if(in.next().equals("n")) {
							break;
						}
				}
				try {
					System.out.println("Market: " + payerSwaptions.getValue(maturity, termination, moneyness));
				} catch (Exception e) {
					System.out.println("Market data not available.");
				}
				break;

			case "r" :
				System.out.println("Value of CSReceiverSwaption, moneyness "+moneyness+", maturity "+maturity+", termination "+termination);
				try {
					System.out.println("Model: "+ receiverValue(model.addVolatilityCube(cube),maturity, termination, moneyness));
				} catch (Exception e) {
					System.out.println("Model failed to evaluate.");
					System.out.println("Print stack trace? y/n");
					while(in.hasNext())
						if(in.next().equals("y"))
						{ e.printStackTrace(); break; }
						else if(in.next().equals("n")) {
							break;
						}
				}
				try {
					System.out.println("Market: " + receiverSwaptions.getValue(maturity, termination, moneyness));
				} catch (Exception e) {
					System.out.println("Market data not available.");
				}
				break;
			}
		}
	}

	private double payerValue(VolatilityCubeModel model, int maturity, int termination, int moneyness) {

		Schedule fixSchedule = fixMetaSchedule.generateSchedule(referenceDate, maturity, termination);
		Schedule floatSchedule = floatMetaSchedule.generateSchedule(referenceDate, maturity, termination);
		double forwardSwapRate	= Swap.getForwardSwapRate(fixSchedule, floatSchedule, model.getForwardCurve(forwardCurveName), model);
		double strike = forwardSwapRate + moneyness/10000.0;

		CashSettledPayerSwaption css = new CashSettledPayerSwaption(fixSchedule, floatSchedule, strike, discountCurveName, forwardCurveName,
				cube.getName(), type, replicationLowerBound, replicationUpperBound, replicationNumberOfEvaluationPoints);
		return css.getValue(floatSchedule.getFixing(0), model);
	}

	private double receiverValue(VolatilityCubeModel model, int maturity, int termination, int moneyness) {

		Schedule fixSchedule = fixMetaSchedule.generateSchedule(referenceDate, maturity, termination);
		Schedule floatSchedule = floatMetaSchedule.generateSchedule(referenceDate, maturity, termination);
		double forwardSwapRate	= Swap.getForwardSwapRate(fixSchedule, floatSchedule, model.getForwardCurve(forwardCurveName), model);
		double strike = forwardSwapRate - moneyness/10000.0;

		CashSettledReceiverSwaption css = new CashSettledReceiverSwaption(fixSchedule, floatSchedule, strike, discountCurveName, forwardCurveName,
				cube.getName(), type, replicationLowerBound, replicationUpperBound, replicationNumberOfEvaluationPoints);
		return css.getValue(floatSchedule.getFixing(0), model);
	}

}
