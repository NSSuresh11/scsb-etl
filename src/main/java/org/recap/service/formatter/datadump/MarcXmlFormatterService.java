package org.recap.service.formatter.datadump;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.marc4j.MarcReader;
import org.marc4j.MarcWriter;
import org.marc4j.MarcXmlReader;
import org.marc4j.MarcXmlWriter;
import org.marc4j.marc.ControlField;
import org.marc4j.marc.DataField;
import org.marc4j.marc.MarcFactory;
import org.marc4j.marc.Record;
import org.marc4j.marc.Subfield;
import org.recap.ScsbCommonConstants;
import org.recap.ScsbConstants;
import org.recap.model.ILSConfigProperties;
import org.recap.model.jpa.BibliographicEntity;
import org.recap.model.jpa.HoldingsEntity;
import org.recap.model.jpa.ItemEntity;
import org.recap.util.PropertyUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

/**
 * Created by premkb on 28/9/16.
 */
@Slf4j
@Service
@Scope("prototype")
public class MarcXmlFormatterService implements DataDumpFormatterInterface {


    @Autowired
    PropertyUtil propertyUtil;

    private MarcFactory factory;

    /**
     * Returns true if selected file format is Marc Xml format for deleted records data dump.
     *
     * @param formatType the format type
     * @return
     */
    @Override
    public boolean isInterested(String formatType) {
        return formatType.equals(ScsbConstants.DATADUMP_XML_FORMAT_MARC);
    }


    /**
     * Prepare a map with marc records and failures for list of bibliographic entities.
     *
     * @param bibliographicEntities the bibliographic entities
     * @return the map
     */
    public Map<String, Object> prepareMarcRecords(List<BibliographicEntity> bibliographicEntities) {
        Map<String, Object> resultsMap = new HashMap<>();
        List<Record> records = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int itemExportedCount = 0;
        for (BibliographicEntity bibliographicEntity : bibliographicEntities) {
            if (CollectionUtils.isNotEmpty(bibliographicEntity.getItemEntities())) {
                Map<String, Object> stringObjectMap = prepareMarcRecord(bibliographicEntity);
                Record record = (Record) stringObjectMap.get(ScsbCommonConstants.SUCCESS);
                if (null != record) {
                    records.add(record);
                    itemExportedCount = itemExportedCount + bibliographicEntity.getItemEntities().size();
                }
                String failureMsg = (String) stringObjectMap.get(ScsbCommonConstants.FAILURE);
                if (null != failureMsg) {
                    errors.add(failureMsg);
                }
            }
        }

        resultsMap.put(ScsbCommonConstants.SUCCESS, records);
        resultsMap.put(ScsbCommonConstants.FAILURE, errors);
        resultsMap.put(ScsbConstants.ITEM_EXPORTED_COUNT, itemExportedCount);

        return resultsMap;
    }

    /**
     * Prepare a map with marc record or failure for a bibliographic entity.
     *
     * @param bibliographicEntity the bibliographic entity
     * @return the map
     */
    public Map<String, Object> prepareMarcRecord(BibliographicEntity bibliographicEntity) {
        Record record;
        Map<String, Object> results = new HashMap<>();
        try {
            record = getRecordFromContent(bibliographicEntity.getContent());
            update001Field(record, bibliographicEntity);
            stripTagsFromBib(record,Arrays.asList(ScsbConstants.MarcFields.DF_852, ScsbConstants.MarcFields.DF_876));
            add009Field(record, bibliographicEntity);
            if(bibliographicEntity.getMatchingIdentity() != null) {
                add901Field(record, bibliographicEntity);
            }
            List<Integer> itemIds = getItemIds(bibliographicEntity);
            log.info("itemids size  >>>>>> " + itemIds.size());
            record = addHoldingInfo(record, bibliographicEntity.getHoldingsEntities(),itemIds,getNonOrphanHoldingsIdList(bibliographicEntity.getItemEntities()));
            results.put(ScsbCommonConstants.SUCCESS, record);
        } catch (Exception e) {
            log.info("failed bib own ins bib id--->{} " , bibliographicEntity.getOwningInstitutionBibId());
            log.error(ScsbConstants.ERROR,e);
            results.put(ScsbCommonConstants.FAILURE,bibliographicEntity.getOwningInstitutionBibId()+" * "+ e);
        }
        return results;
    }

    /**
     * Remove selected tags from marc record.
     * @param record
     * @param tagList
     */
    private static void stripTagsFromBib(Record record,List<String> tagList){
        for(Iterator<DataField> dataFieldIterator = record.getDataFields().iterator(); dataFieldIterator.hasNext();) {
            DataField dataField = dataFieldIterator.next();
            for (String tag : tagList) {
                if (tag.equals(dataField.getTag())) {
                    dataFieldIterator.remove();
                }
            }
        }
    }

    /**
     * Gets item ids from bibliographic entity
     * @param bibliographicEntity
     * @return
     */
    private static List<Integer> getItemIds(BibliographicEntity bibliographicEntity){
        List<Integer> itemIds = new ArrayList<>();
        List<ItemEntity> itemEntityList = bibliographicEntity.getItemEntities();
        for(ItemEntity itemEntity : itemEntityList){
            itemIds.add(itemEntity.getId());
        }
        return itemIds;
    }

    /**
     * This method is to filter the holdings which are belonging to share and open item and not belonging private item
     * @param itemEntityList
     * @return
     */
    private static List<Integer> getNonOrphanHoldingsIdList(List<ItemEntity> itemEntityList){
        Set<Integer> holdingsIdSet = new HashSet<>();
        log.info("itemEntityList size >>>>>>> " + itemEntityList.size());
        if (!itemEntityList.isEmpty()) {
            for (ItemEntity itemEntity : itemEntityList) {
                for (HoldingsEntity holdingsEntity : itemEntity.getHoldingsEntities()) {
                    holdingsIdSet.add(holdingsEntity.getId());
                }
            }
        }
        return new ArrayList<>(holdingsIdSet);
    }
    /**
     * Build marc record from byte array marc content.
     * @param content
     * @return
     */
    private static Record getRecordFromContent(byte[] content) {
        MarcReader reader;
        Record record = null;
        InputStream inputStream = new ByteArrayInputStream(content);
        reader = new MarcXmlReader(inputStream);
        while (reader.hasNext()) {
            record = reader.next();
        }
        return record;
    }

    /**
     * Set 00l control field value with SCSB bibliographic id in the marc record.
     * @param record
     * @param bibliographicEntity
     */
    private void update001Field(Record record, BibliographicEntity bibliographicEntity) {
        boolean is001Available = false;
        for (ControlField controlField : record.getControlFields()) {
            if (ScsbConstants.MarcFields.CF_001.equals(controlField.getTag())) {
                controlField.setData(ScsbConstants.SCSB + "-" + bibliographicEntity.getId());
                is001Available = true;
            }
        }
        if(!is001Available) {
            ControlField controlField = getFactory().newControlField(ScsbConstants.MarcFields.CF_001);
            controlField.setData(ScsbConstants.SCSB + "-" + bibliographicEntity.getId());
            record.addVariableField(controlField);
        }
    }

    private void add009Field(Record record, BibliographicEntity bibliographicEntity){
        ControlField controlField = getFactory().newControlField(ScsbConstants.MarcFields.CF_009);
        controlField.setData(bibliographicEntity.getOwningInstitutionBibId());
        record.addVariableField(controlField);
    }

    private void add901Field(Record record, BibliographicEntity bibliographicEntity){
        DataField dataField = getFactory().newDataField(ScsbConstants.MarcFields.DF_901, ' ', ' ');
        dataField.addSubfield(getFactory().newSubfield('a', bibliographicEntity.getMatchingIdentity() != null ? bibliographicEntity.getMatchingIdentity() : ""));
        dataField.addSubfield(getFactory().newSubfield('b', bibliographicEntity.getMatchingIdentity() != null ? String.valueOf(bibliographicEntity.getMatchScore()) : ""));
        dataField.addSubfield(getFactory().newSubfield('c', bibliographicEntity.getMatchingIdentity() != null ? String.valueOf(bibliographicEntity.isAnamolyFlag()) : ""));
        record.addVariableField(dataField);
    }

    /**
     * Adds holdings information tags to the marc record.
     * @param record
     * @param holdingsEntityList
     * @param nonOrphanHoldingsIdList
     * @return
     */
    private Record addHoldingInfo(Record record, List<HoldingsEntity> holdingsEntityList,List<Integer> itemIds,List<Integer> nonOrphanHoldingsIdList) {
        Record holdingRecord;
        for (HoldingsEntity holdingsEntity : holdingsEntityList) {
            if (nonOrphanHoldingsIdList !=null && nonOrphanHoldingsIdList.contains(holdingsEntity.getId())) {
                holdingRecord = getRecordFromContent(holdingsEntity.getContent());
                if(holdingRecord != null) {
                    for (DataField dataField : holdingRecord.getDataFields()) {
                        if (ScsbConstants.MarcFields.DF_852.equals(dataField.getTag())) {
                            addOrUpdateDatafield852Subfield0(dataField, holdingsEntity);
                            update852bField(dataField, holdingsEntity);
                            record.addVariableField(dataField);
                        }
                        if (ScsbConstants.MarcFields.DF_866.equals(dataField.getTag())  &&
                            (!(dataField.getSubfield('a') != null && (dataField.getSubfield('a').getData() == null || "".equals(dataField.getSubfield('a').getData()))))) {
                                addOrUpdateDatafield852Subfield0(dataField, holdingsEntity);
                                record.addVariableField(dataField);
                        }
                    }
                }
                for(ItemEntity itemEntity : holdingsEntity.getItemEntities()){
                    if(itemIds.contains(itemEntity.getId())) {
                        record = addItemInfo(record, itemEntity, holdingsEntity);
                    }
                }
            }
        }
        return record;
    }

    /**
     * Adds a '0' subfield with SCSB holdings id to the given data field.
     * @param dataField
     * @param holdingEntity
     */
    private void add0SubField(DataField dataField, HoldingsEntity holdingEntity) {
        dataField.addSubfield(getFactory().newSubfield('0', holdingEntity.getId().toString()));
    }

    /**
     * Updates 852 0 field value with the scsb holding id information.
     * @param dataField
     * @param holdingEntity
     */
    private void addOrUpdateDatafield852Subfield0(DataField dataField, HoldingsEntity holdingEntity){
        List<Subfield> subfields = dataField.getSubfields('0');
        if(CollectionUtils.isNotEmpty(subfields)){
            for(Subfield subfield:subfields){
                if(subfield.getCode()=='0'){
                    subfield.setData(holdingEntity.getId().toString());
                }
            }
        } else {
            add0SubField(dataField,holdingEntity);
        }
    }

    /**
     * Updates 852 b field with the institution information.
     * @param dataField
     * @param holdingEntity
     */
    private void update852bField(DataField dataField, HoldingsEntity holdingEntity){
        String partnerInfo = "";
        List<Subfield> subfields = dataField.getSubfields('b');
        if(CollectionUtils.isNotEmpty(subfields)) {
            for (Iterator<Subfield> iterator = subfields.iterator(); iterator.hasNext(); ) {
                Subfield subfield = iterator.next();
                dataField.removeSubfield(subfield);
            }
        }
        ILSConfigProperties ilsConfigProperties = propertyUtil.getILSConfigProperties(holdingEntity.getInstitutionEntity().getInstitutionCode());
        partnerInfo = ilsConfigProperties.getDatadumpMarc();
        Subfield subfield = factory.newSubfield('b', partnerInfo);
        dataField.addSubfield(subfield);
    }

    /**
     * Adds item information tags to the marc record.
     * @param record
     * @param itemEntity
     * @param holdingsEntity
     * @return
     */
    private Record addItemInfo(Record record, ItemEntity itemEntity,HoldingsEntity holdingsEntity) {
        DataField dataField = getFactory().newDataField(ScsbConstants.MarcFields.DF_876, ' ', ' ');
        dataField.addSubfield(getFactory().newSubfield('0', String.valueOf(holdingsEntity.getId())));
        dataField.addSubfield(getFactory().newSubfield('3', itemEntity.getVolumePartYear() != null ? itemEntity.getVolumePartYear() : ""));
        dataField.addSubfield(getFactory().newSubfield('a', String.valueOf(itemEntity.getId())));
        dataField.addSubfield(getFactory().newSubfield('h', itemEntity.getUseRestrictions() != null ? itemEntity.getUseRestrictions() : ""));
        dataField.addSubfield(getFactory().newSubfield('j', itemEntity.getItemStatusEntity().getStatusCode()));
        dataField.addSubfield(getFactory().newSubfield('k', itemEntity.getItemLibrary() !=null ? itemEntity.getItemLibrary() : ""));
        dataField.addSubfield(getFactory().newSubfield('p', itemEntity.getBarcode()));
        dataField.addSubfield(getFactory().newSubfield('t', itemEntity.getCopyNumber() != null ? String.valueOf(itemEntity.getCopyNumber()) : ""));
        dataField.addSubfield(getFactory().newSubfield('x', itemEntity.getCollectionGroupEntity().getCollectionGroupCode()));
        dataField.addSubfield(getFactory().newSubfield('z', itemEntity.getCustomerCode()));
        dataField.addSubfield(getFactory().newSubfield('l', itemEntity.getImsLocationEntity() != null ? itemEntity.getImsLocationEntity().getImsLocationCode() : ""));
        record.addVariableField(dataField);

        return record;
    }

    /**
     * Covert marc records to marc xml string.
     *
     * @param recordList the record list
     * @return the string
     * @throws Exception the exception
     */
    public String covertToMarcXmlString(List<Record> recordList) throws Exception {
        OutputStream out = new ByteArrayOutputStream();
        MarcWriter writer = new MarcXmlWriter(out, "UTF-8", true);
        recordList.forEach(writer::write);
        writer.close();
        return out.toString();
    }

    /**
     * Gets marc factory.
     *
     * @return the factory
     */
    public MarcFactory getFactory() {
        if (null == factory) {
            factory = MarcFactory.newInstance();
        }
        return factory;
    }
}
