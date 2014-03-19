package cz.cuni.lf1.lge.ThunderSTORM.drift;

import cz.cuni.lf1.lge.ThunderSTORM.results.*;
import cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.Molecule;
import cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.MoleculeDescriptor;
import cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.PSFModel;
import cz.cuni.lf1.lge.ThunderSTORM.estimators.optimizers.NelderMead;
import cz.cuni.lf1.lge.ThunderSTORM.util.MathProxy;
import cz.cuni.lf1.lge.ThunderSTORM.util.VectorMath;
import ij.IJ;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.math3.analysis.MultivariateFunction;
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction;
import org.apache.commons.math3.util.MathArrays;

public class FiducialDriftEstimator {

    public DriftResults estimateDrift(List<Molecule> molecules, double distanceThr, double onTimeRatio, double smoothingBandwidth) {
        int minFrame = (int) getMinFrame(molecules);
        int maxFrame = (int) getMaxFrame(molecules);

        //group molecules appearing in subsequent frames
        IJ.showStatus("Grouping molecules...");
        List<Molecule> groupedMolecules = groupMolecules(molecules, distanceThr);

        //select fiducial markers (molecules that are on for many frames)
        List<Molecule> fiducialMarkers = new ArrayList<Molecule>();
        for(Molecule mol : groupedMolecules) {
            if(mol.getParam(MoleculeDescriptor.LABEL_DETECTIONS) > onTimeRatio * (maxFrame - minFrame)) {
                fiducialMarkers.add(mol);
            }
        }
        //combine data points from multiple fiducial markers
        int dataPoints = countDetections(fiducialMarkers);
        double[] combinedFrames = new double[dataPoints];
        double[] combinedX = new double[dataPoints];
        double[] combinedY = new double[dataPoints];
        //combine frame data values
        int lastIndex = 0;
        for(Molecule mol : fiducialMarkers) {
            List<Molecule> detections = mol.getDetections();
            double[] frame = extractParamAsArray(detections, detections.get(0).descriptor.getParamIndex(MoleculeDescriptor.LABEL_FRAME));
            System.arraycopy(frame, 0, combinedFrames, lastIndex, frame.length);
            lastIndex += frame.length;
        }
        //find offsets for each fiducial marker (to get relative drift out of absolute coordinates)
        IJ.showStatus("Finding marker offsets (x)...");
        double[] markerOffsetsInX = findFiducialsOffsets(fiducialMarkers, combinedFrames, PSFModel.Params.LABEL_X);
        IJ.showStatus("Finding marker offsets (y)...");
        double[] markerOffsetsInY = findFiducialsOffsets(fiducialMarkers, combinedFrames, PSFModel.Params.LABEL_Y);
        //combine x,y, while subtracting the found offsets
        lastIndex = 0;
        for(int i = 0; i < fiducialMarkers.size(); i++) {
            List<Molecule> detections = fiducialMarkers.get(i).getDetections();
            double[] x = extractParamAsArray(detections, detections.get(0).descriptor.getParamIndex(PSFModel.Params.LABEL_X));
            double[] y = extractParamAsArray(detections, detections.get(0).descriptor.getParamIndex(PSFModel.Params.LABEL_Y));

            VectorMath.add(x, -markerOffsetsInX[i]);
            VectorMath.add(y, -markerOffsetsInY[i]);

            System.arraycopy(x, 0, combinedX, lastIndex, x.length);
            System.arraycopy(y, 0, combinedY, lastIndex, y.length);

            lastIndex += x.length;
        }
        //sort, because loess interpolation needs non descending domain values
        MathArrays.sortInPlace(combinedFrames, combinedX, combinedY);

        //subtract first frame coordinates so that drift at first frame is zero
        //Could be a problem when first frame drift is off. ??
        VectorMath.add(combinedX, -combinedX[0]);
        VectorMath.add(combinedY, -combinedY[0]);

        
        //smooth & interpolate
        IJ.showStatus("Smoothing and interpolating drift...");
        ModifiedLoess interpolator = new ModifiedLoess(smoothingBandwidth, 0);
        PolynomialSplineFunction xFunction = CorrelationDriftEstimator.addLinearExtrapolationToBorders(interpolator.interpolate(combinedFrames, combinedX), minFrame, maxFrame);
        PolynomialSplineFunction yFunction = CorrelationDriftEstimator.addLinearExtrapolationToBorders(interpolator.interpolate(combinedFrames, combinedY), minFrame, maxFrame);

        //same units as input
        MoleculeDescriptor.Units units = molecules.get(0).getParamUnits(PSFModel.Params.LABEL_X);
        return new DriftResults(xFunction, yFunction, combinedFrames, combinedX, combinedY, minFrame, maxFrame, units);
    }

    private int countDetections(List<Molecule> fiducialMarkers) {
        int dataPoints = 0;
        for(Molecule mol : fiducialMarkers) {
            dataPoints += mol.getDetections().size();
        }
        return dataPoints;
    }

    private List<Molecule> groupMolecules(List<Molecule> molecules, double distanceThr) {
        FrameSequence grouping = new FrameSequence();
        for(Molecule mol : molecules) {
            grouping.InsertMolecule(mol);
        }
        grouping.matchMolecules(MathProxy.sqr(distanceThr),
                new FrameSequence.RelativeToDetectionCount(2),
                new FrameSequence.LastFewDetectionsMean(5),
                0);
        List<Molecule> groupedMolecules = grouping.getAllMolecules();
        return groupedMolecules;
    }

    public double[] findFiducialsOffsets(final List<Molecule> fiducials, final double[] combinedFrames, String param) {

        //frame to detection coordinate maps, for each fiducial marker
        final List<Map<Double, Double>> maps = new ArrayList<Map<Double, Double>>();
        for(Molecule fiducial : fiducials) {
            Map<Double, Double> detectionsByFrame = new HashMap<Double, Double>();
            maps.add(detectionsByFrame);
            for(Molecule detection : fiducial.getDetections()) {
                detectionsByFrame.put(detection.getParam(MoleculeDescriptor.LABEL_FRAME), detection.getParam(param));
            }
        }

        NelderMead nm = new NelderMead();
        //cost function:
        //for each frame where multiple drift values are present
        // cost += square of difference between each drift value and mean drift value for that frame
        MultivariateFunction fun = new MultivariateFunction() {
            @Override
            public double value(double[] point) {
                double cost = 0;
                for(double frame : combinedFrames) {
                    List<Double> drifts = new ArrayList<Double>();
                    for(int i = 0; i < fiducials.size(); i++) {
                        Double val = maps.get(i).get(frame);
                        if(val != null) {
                            drifts.add(val - point[i]);
                        }
                    }
                    if(drifts.size() > 1) {
                        double sum = 0;
                        for(Double d : drifts) {
                            sum += d;
                        }
                        double avg = sum / drifts.size();

                        for(Double d : drifts) {
                            cost += MathProxy.sqr(d - avg);
                        }
                    }
                }
                return Math.sqrt(cost);
            }
        };
        //values for first iteration:  first detection coords
        double[] guess = new double[fiducials.size()];
        for(int i = 0; i < guess.length; i++) {
            fiducials.get(i).getDetections().get(0).getParam(param);
        }
        //first simplex step size, ?????
        double[] initialSimplex = new double[fiducials.size()];
        Arrays.fill(initialSimplex, 5);
        int maxIter = 5000;

        nm.optimize(fun, NelderMead.Objective.MINIMIZE, guess, 1e-8, initialSimplex, 10, maxIter);
        double[] fittedParameters = nm.xmin;

        return fittedParameters;
    }

    static double[] extractParamAsArray(List<Molecule> mols, int index) {
        double[] arr = new double[mols.size()];
        for(int i = 0; i < mols.size(); i++) {
            arr[i] = mols.get(i).getParamAt(index);
        }
        return arr;
    }

    private double getMinFrame(List<Molecule> molecules) {
        double min = molecules.get(0).getParam(MoleculeDescriptor.LABEL_FRAME);
        for(Molecule mol : molecules) {
            double frame = mol.getParam(MoleculeDescriptor.LABEL_FRAME);
            if(frame < min) {
                min = frame;
            }
        }
        return min;
    }

    private double getMaxFrame(List<Molecule> molecules) {
        double max = molecules.get(0).getParam(MoleculeDescriptor.LABEL_FRAME);
        for(Molecule mol : molecules) {
            double frame = mol.getParam(MoleculeDescriptor.LABEL_FRAME);
            if(frame > max) {
                max = frame;
            }
        }
        return max;
    }
}
