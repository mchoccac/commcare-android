package org.commcare.print;

import android.os.AsyncTask;
import android.os.Bundle;

import org.commcare.google.services.analytics.GoogleAnalyticsFields;
import org.commcare.google.services.analytics.GoogleAnalyticsUtils;
import org.commcare.utils.PrintValidationException;
import org.commcare.utils.TemplatePrinterUtils;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;


/**
 * Asynchronous task for populating an html document with data.
 *
 * @author Richard Lu
 * @author amstone
 */
public class TemplatePrinterTask extends AsyncTask<Void, Void, TemplatePrinterTask.PrintTaskResult> {

    public enum PrintTaskResult {
        SUCCESS, IO_ERROR, VALIDATION_ERROR_MUSTACHE, VALIDATION_ERROR_CHEVRON
    }

    /**
     * Used to track the region in the template file where a validation error was encountered
     */
    private String problemString;

    /**
     * The mapping from keywords to case property values to be used in populating the template
     */
    private final Bundle templatePopulationMapping;

    private final File templateFile;
    private final String populatedFilepath;
    private final PopulateListener listener;


    public TemplatePrinterTask(File input, String outputPath, Bundle values,
                               PopulateListener listener) {
        this.templateFile = input;
        this.populatedFilepath = outputPath;
        this.templatePopulationMapping = values;
        this.listener = listener;
    }

    /**
     * Attempts to perform population of the template file
     */
    @Override
    protected PrintTaskResult doInBackground(Void... params) {
        GoogleAnalyticsUtils.reportFeatureUsage(GoogleAnalyticsFields.ACTION_PRINT);
        try {
            populateAndSaveHtml(templateFile, templatePopulationMapping, populatedFilepath);
            return PrintTaskResult.SUCCESS;
        } catch (IOException e) {
            return PrintTaskResult.IO_ERROR;
        } catch (PrintValidationException e) {
            problemString = e.getMessage();
            return e.getErrorType();
        }
    }

    /**
     * Receives the return value from doInBackground and proceeds accordingly
     */
    @Override
    protected void onPostExecute(PrintTaskResult result) {
        listener.onPopulationFinished(result, problemString);
    }

    /**
     * Populates an html print template based on the given set of key-value pairings
     * and save the newly-populated template to a temp location
     *
     * @param input   the html print template
     * @param mapping the mapping of keywords to case property values
     * @throws IOException, PrintValidationException
     */
    private static void populateAndSaveHtml(File input, Bundle mapping, String outputPath)
            throws IOException, PrintValidationException {

        // Read from input file
        String fileText = TemplatePrinterUtils.docToString(input);

        // Check if <body></body> section of html string is properly formed
        int startBodyIndex = fileText.toLowerCase().indexOf("<body");
        String beforeBodySection = fileText.substring(0, startBodyIndex);
        String bodySection = fileText.substring(startBodyIndex);
        validateString(bodySection);

        // Swap out place-holder keywords for case property values within <body></body> section
        bodySection = replace(bodySection, mapping);

        // Write the new HTML to the desired  temp file location
        TemplatePrinterUtils.writeStringToFile(beforeBodySection + bodySection, outputPath);
    }


    /**
     * Populate an input string with attribute keys formatted as {{ attr_key }} with attribute
     * values.
     *
     * @param input   String input
     * @param mapping Bundle of key-value mappings with which to complete replacements
     * @return The populated String
     */
    private static String replace(String input, Bundle mapping) {
        // Split input into tokens bounded by {{ and }}
        String[] tokens = TemplatePrinterUtils.splitKeepDelimiter(input, "\\{{2}", "\\}{2}");
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];

            // Every 2nd token is a attribute enclosed in {{ }}
            if (i % 2 == 1) {

                // Split token into tokenSplits bounded by < and >
                String[] tokenSplits = TemplatePrinterUtils.splitKeepDelimiter(token, "<|(\\}{2})", ">|(\\{{2})");

                // First and last tokenSplits are {{ and }}
                for (int j = 1; j < tokenSplits.length - 1; j++) {
                    String tokenSplit = tokenSplits[j];
                    // tokenSplit is key or whitespace
                    if (!tokenSplit.startsWith("<")) {
                        replaceKeyWithValue(mapping, tokenSplit, tokenSplits, j);
                    }
                }

                // Remove {{ and }}
                tokenSplits[0] = "";
                tokenSplits[tokenSplits.length - 1] = "";

                // Reconstruct token
                tokens[i] = TemplatePrinterUtils.join(tokenSplits);
            }
        }

        // Reconstruct input
        return TemplatePrinterUtils.join(tokens);
    }

    private static void replaceKeyWithValue(Bundle mapping, String tokenSplit,
                                            String[] tokenSplits, int index) {
        // Remove whitespace from key
        String key = TemplatePrinterUtils.remove(tokenSplit, " ");

        String valToPopulate = "";
        if (mapping.containsKey(key) && mapping.get(key) != null) {
            Serializable passedValue = mapping.getSerializable(key);
            if (passedValue instanceof PrintableDetailField) {
                // If we are printing from a detail, the passed values will be of type
                // PrintableDetailField
                PrintableDetailField printableField = ((PrintableDetailField)passedValue);
                if (printableField.isPrintSupported()) {
                    valToPopulate = printableField.getFormattedValueString();
                } else {
                    // Empty if printing is not supported for this type of detail field
                    valToPopulate = "";
                }
            } else {
                // If we are printing from a form, the passed values will just be strings
                valToPopulate = (String)passedValue;
            }
        }
        tokenSplits[index] = valToPopulate;
    }

    /**
     * Validates the input string for well-formed {{ }} and < > pairs.
     * If malformed, throws an exception that will be caught by
     * doInBackground(), and trigger the appropriate result code to be
     * sent back to the attached PopulateListener
     *
     * @param input String to validate
     */
    private static void validateString(String input) throws PrintValidationException {

        boolean isBetweenMustaches = false;
        boolean isBetweenChevrons = false;
        StringBuilder recentString = new StringBuilder();

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            recentString.append(c);

            if (recentString.length() > 40) {
                recentString.deleteCharAt(0);
            }

            if (c == '{') {
                if (isBetweenMustaches) {
                    throw new PrintValidationException(recentString.toString(),
                            PrintTaskResult.VALIDATION_ERROR_MUSTACHE);
                } else {
                    i++;
                    c = input.charAt(i);
                    if (c == '{') {
                        isBetweenMustaches = true;
                        recentString.append(c);
                    } else {
                        isBetweenMustaches = false;
                    }
                }
            } else if (c == '}') {
                if (isBetweenMustaches) {
                    i++;
                    c = input.charAt(i);
                    if (c != '}') {
                        recentString.append(c);
                        throw new PrintValidationException(recentString.toString(),
                                PrintTaskResult.VALIDATION_ERROR_MUSTACHE);
                    } else {
                        isBetweenMustaches = false;
                    }
                }
            } else if (c == '<') {
                if (isBetweenChevrons) {
                    throw new PrintValidationException(recentString.toString(),
                            PrintTaskResult.VALIDATION_ERROR_CHEVRON);
                } else {
                    isBetweenChevrons = true;
                }
            } else if (c == '>') {
                if (isBetweenChevrons) {
                    isBetweenChevrons = false;
                } else {
                    throw new PrintValidationException(recentString.toString(),
                            PrintTaskResult.VALIDATION_ERROR_CHEVRON);
                }
            }
        }

        // If we reach the end of the string and are in between either type, should also throw error
        if (isBetweenChevrons) {
            throw new PrintValidationException(recentString.toString(),
                    PrintTaskResult.VALIDATION_ERROR_CHEVRON);
        } else if (isBetweenMustaches) {
            throw new PrintValidationException(recentString.toString(),
                    PrintTaskResult.VALIDATION_ERROR_MUSTACHE);
        }

    }

    /**
     * A listener for this task, implemented by TemplatePrinterActivity
     */
    public interface PopulateListener {
        void onPopulationFinished(PrintTaskResult result, String problemString);
    }

}
