package org.recap.service.formatter.datadump;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.recap.ScsbCommonConstants;
import org.recap.ScsbConstants;
import org.recap.model.jaxb.*;
import org.recap.model.jaxb.marc.*;
import org.recap.model.jpa.BibliographicEntity;
import org.recap.model.jpa.HoldingsEntity;
import org.recap.model.jpa.ItemEntity;
import org.recap.model.jpa.MatchingBibInfoDetail;
import org.recap.repository.MatchingBibInfoDetailRepository;
import org.recap.repositoryrw.ReportDataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Created by premkb on 28/9/16.
 */
@Slf4j
@Service
@Scope("prototype")
public class SCSBXmlFormatterService implements DataDumpFormatterInterface {


    @Autowired
    private ReportDataRepository reportDataRepository;

    @Autowired
    private MatchingBibInfoDetailRepository matchingBibInfoDetailRepository;

    /**
     * Returns true if selected file format is SCSB Xml format for deleted records data dump.
     * @param formatType the format type
     * @return
     */
    @Override
    public boolean isInterested(String formatType) {
        return formatType.equals(ScsbConstants.DATADUMP_XML_FORMAT_SCSB) ? Boolean.TRUE.booleanValue():Boolean.FALSE.booleanValue();
    }

    /**
     * Gets scsb format xml for list of bib records.
     *
     * @param bibRecords the bib records
     * @return the scsb xml for bib records
     * @throws Exception the exception
     */
    public String getSCSBXmlForBibRecords(List<BibRecord> bibRecords) throws Exception{
        String formattedString;
        BibRecords bibRecords1 = new BibRecords();
        bibRecords1.setBibRecordList(bibRecords);
        formattedString = convertToXml(bibRecords1);

        return formattedString;
    }

    /**
     * Convert bib records list to scsb format xml.
     * @param bibRecords
     * @return
     * @throws Exception
     */
    private String convertToXml(BibRecords bibRecords) throws Exception {
        StringWriter stringWriter = new StringWriter();
        Marshaller jaxbMarshaller = JAXBContextHandler.getInstance().getJAXBContextForClass(BibRecords.class).createMarshaller();
        synchronized (jaxbMarshaller) {
            jaxbMarshaller.marshal(bibRecords, stringWriter);
        }
        return stringWriter.toString();
    }

    /**
     * Prepare a map with bib records and failures for list of bibliographic entities.
     *
     * @param bibliographicEntities the bibliographic entities
     * @return the map
     */
    public Map<String, Object> prepareBibRecords(List<BibliographicEntity> bibliographicEntities) {
        int itemExportedCount = 0;
        Map<String, Object> resultsMap = new HashMap<>();
        List<BibRecord> records = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> bibIdList = getBibIdList(bibliographicEntities);
        Map<String,Integer> bibIdRecordNumMap = null;
        List<Integer> recordNumList = matchingBibInfoDetailRepository.getRecordNum(bibIdList);
        Map<Integer,List<MatchingBibInfoDetail>> recordNumMatchingBibInfoDetailMap = null;
        if (recordNumList != null && !recordNumList.isEmpty()) {
            List<MatchingBibInfoDetail> matchingBibInfoDetailList = matchingBibInfoDetailRepository.findByRecordNum(recordNumList);
            recordNumMatchingBibInfoDetailMap = null;
            if(recordNumList != null && !recordNumList.isEmpty()){
                bibIdRecordNumMap = getBibIdRowNumMap(matchingBibInfoDetailList);// put bib id and record num from report table in a map
                recordNumMatchingBibInfoDetailMap = getRecordNumReportDataEntityMap(matchingBibInfoDetailList);
            }
        }

        for (Iterator<BibliographicEntity> bibliographicEntityIterator = bibliographicEntities.iterator(); bibliographicEntityIterator.hasNext(); ) {
            BibliographicEntity bibliographicEntity = bibliographicEntityIterator.next();
            if(CollectionUtils.isNotEmpty(bibliographicEntity.getItemEntities())) {
                List<MatchingBibInfoDetail> matchingBibInfoDetailListForSingleBib = null;
                if(bibIdRecordNumMap!=null){
                    Integer rowNum = bibIdRecordNumMap.get(String.valueOf(bibliographicEntity.getId()));
                    matchingBibInfoDetailListForSingleBib = recordNumMatchingBibInfoDetailMap.get(rowNum);
                }
                Map<String, Object> stringObjectMap = prepareBibRecord(bibliographicEntity,matchingBibInfoDetailListForSingleBib);
                BibRecord bibRecord = (BibRecord) stringObjectMap.get(ScsbCommonConstants.SUCCESS);
                if (null != bibRecord) {
                    records.add(bibRecord);
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
     * To put bib id and record num from report table in a map.
     * @param matchingBibInfoDetailList
     * @return
     */
    private Map<Integer, List<MatchingBibInfoDetail>> getRecordNumReportDataEntityMap(List<MatchingBibInfoDetail> matchingBibInfoDetailList) {
        Map<Integer, List<MatchingBibInfoDetail>> recordNumMatchingBibInfoDetailMap = new HashMap<>();
        for (MatchingBibInfoDetail matchingBibInfoDetail : matchingBibInfoDetailList) {
            if (recordNumMatchingBibInfoDetailMap.containsKey(matchingBibInfoDetail.getRecordNum())) {
                recordNumMatchingBibInfoDetailMap.get(matchingBibInfoDetail.getRecordNum()).add(matchingBibInfoDetail);
            } else {
                List<MatchingBibInfoDetail> reportDataEntityListForRowNum = new ArrayList<>();
                reportDataEntityListForRowNum.add(matchingBibInfoDetail);
                recordNumMatchingBibInfoDetailMap.put(matchingBibInfoDetail.getRecordNum(), reportDataEntityListForRowNum);
            }
        }
        return recordNumMatchingBibInfoDetailMap;
    }

    /**
     * Gets bib id list from list of bibliographic entities.
     * @param bibliographicEntityList
     * @return
     */
    private List<String> getBibIdList(List<BibliographicEntity> bibliographicEntityList){
        List<String> bibIdList = new ArrayList<>();
        for(BibliographicEntity bibliographicEntity : bibliographicEntityList){
            bibIdList.add(String.valueOf(bibliographicEntity.getId()));
        }
        return bibIdList;
    }

    /**
     * Prepares a map with bib id as key annd matching bib record num as value using matching bib info list.
     * @param matchingBibInfoDetailList
     * @return
     */
    private Map<String,Integer> getBibIdRowNumMap(List<MatchingBibInfoDetail> matchingBibInfoDetailList){
        Map<String,Integer> bibIdRownumMap = new HashMap<>();
        for(MatchingBibInfoDetail matchingBibInfoDetail: matchingBibInfoDetailList){
            bibIdRownumMap.put(matchingBibInfoDetail.getBibId(),matchingBibInfoDetail.getRecordNum());
        }
        return bibIdRownumMap;
    }

    /**
     * Prepare a map with bib record or failure for a bibliographic entity.
     * @param bibliographicEntity
     * @param matchingBibInfoDetailList
     * @return
     */
    private Map<String, Object> prepareBibRecord(BibliographicEntity bibliographicEntity,List<MatchingBibInfoDetail> matchingBibInfoDetailList) {
        BibRecord bibRecord = null;
        Map<String, Object> results = new HashMap<>();
        try {
            Bib bib = getBib(bibliographicEntity,matchingBibInfoDetailList);
            List<Integer> itemIds = getItemIds(bibliographicEntity);
            List<Holdings> holdings = getHoldings(bibliographicEntity.getHoldingsEntities(),itemIds,getNonOrphanHoldingsIdList(bibliographicEntity.getItemEntities()));
            bibRecord = new BibRecord();
            bibRecord.setBib(bib);
            bibRecord.setHoldings(holdings);
            results.put(ScsbCommonConstants.SUCCESS, bibRecord);
        } catch (Exception e) {
            log.info(String.format("Exception for BIB Record %s", bibliographicEntity.getOwningInstitutionBibId()));
            log.error(ScsbConstants.ERROR,e);
            results.put(ScsbCommonConstants.FAILURE, bibliographicEntity.getOwningInstitutionBibId()+" * "+String.valueOf(e));
        }
        return results;
    }

    private List<Integer> getNonOrphanHoldingsIdList(List<ItemEntity> itemEntityList){
        Set<Integer> holdingsIdSet = new HashSet<>();
        for(ItemEntity itemEntity:itemEntityList){
            for(HoldingsEntity holdingsEntity:itemEntity.getHoldingsEntities()){
                holdingsIdSet.add(holdingsEntity.getId());
            }        }
        return new ArrayList<>(holdingsIdSet);
    }

    /**
     * Gets item ids for the given bibliographic entity.
     * @param bibliographicEntity
     * @return
     */
    private List<Integer> getItemIds(BibliographicEntity bibliographicEntity){
        List<Integer> itemIds = new ArrayList<>();
        List<ItemEntity> itemEntityList = bibliographicEntity.getItemEntities();
        for(ItemEntity itemEntity : itemEntityList){
            itemIds.add(itemEntity.getId());
        }
        return itemIds;
    }

    /**
     * Gets bib from bibliographic entity.
     * @param bibliographicEntity
     * @param matchingBibInfoDetailList
     * @return
     * @throws Exception
     */
    private Bib getBib(BibliographicEntity bibliographicEntity, List<MatchingBibInfoDetail> matchingBibInfoDetailList) throws Exception{
        Bib bib = new Bib();
        bib.setOwningInstitutionBibId(bibliographicEntity.getOwningInstitutionBibId());
        bib.setOwningInstitutionId(bibliographicEntity.getInstitutionEntity().getInstitutionCode());
        if(matchingBibInfoDetailList!=null){
            bib.setMatchingInstitutionBibId(getMatchingInstitutionBibId(String.valueOf(bibliographicEntity.getId()),matchingBibInfoDetailList));
        }
        ContentType contentType = getContentType(bibliographicEntity.getContent());
        List<RecordType> record = contentType.getCollection().getRecord();
        RecordType recordType = record.get(0);
        String value = ScsbConstants.SCSB+"-"+bibliographicEntity.getId();
        if(!recordType.getControlfield().isEmpty()) {
            recordType.getControlfield().get(0).setValue(value);
        }
        if(bibliographicEntity.getMatchingIdentity() != null) {
            recordType.getDatafield().add(add901Field(record, bibliographicEntity));
        }
        bib.setContent(contentType);
        return bib;
    }

    /**
     * Gets matching institution bib id from list of matching bib information details.
     * @param bibId
     * @param matchingBibInfoDetailList
     * @return
     */
    private List<MatchingInstitutionBibIdType> getMatchingInstitutionBibId(String bibId, List<MatchingBibInfoDetail> matchingBibInfoDetailList) {
        List<MatchingInstitutionBibIdType> matchingInstitutionBibIdTypeList = new ArrayList<>();
        for(MatchingBibInfoDetail matchingBibInfoDetail:matchingBibInfoDetailList){
            if(!bibId.equals(matchingBibInfoDetail.getBibId())){
                MatchingInstitutionBibIdType matchingInstitutionBibIdType = new MatchingInstitutionBibIdType();
                matchingInstitutionBibIdType.setSource(matchingBibInfoDetail.getOwningInstitution());
                matchingInstitutionBibIdType.setValue(matchingBibInfoDetail.getOwningInstitutionBibId());
                matchingInstitutionBibIdTypeList.add(matchingInstitutionBibIdType);
            }
        }
        return matchingInstitutionBibIdTypeList;
    }

    /**
     * Gets holdinds records for the given holdings entities.
     * @param holdingsEntityList
     * @param itemIds
     * @return
     * @throws Exception
     */
    private List<Holdings> getHoldings(List<HoldingsEntity> holdingsEntityList, List<Integer> itemIds, List<Integer> nonOrphanHoldingsIdList) throws Exception{
        List<Holdings> holdingsList = new ArrayList<>();
        if (holdingsEntityList!=null && !CollectionUtils.isEmpty(holdingsEntityList)) {
            for (HoldingsEntity holdingsEntity : holdingsEntityList) {
                if (nonOrphanHoldingsIdList !=null && nonOrphanHoldingsIdList.contains(holdingsEntity.getId())) {
                    Holdings holdings = new Holdings();
                    Holding holding = new Holding();
                    holding.setOwningInstitutionHoldingsId(holdingsEntity.getOwningInstitutionHoldingsId());
                    ContentType contentType = getContentType(holdingsEntity.getContent());
                    holding.setContent(contentType);
                    List<ItemEntity> itemEntityList = new ArrayList<>();
                    if(holdingsEntity.getItemEntities()!=null) {
                        for(ItemEntity itemEntity:holdingsEntity.getItemEntities()){
                            if(itemIds.contains(itemEntity.getId())) {
                                itemEntityList.add(itemEntity);
                            }
                        }
                        Items items = getItems(itemEntityList);
                        holding.setItems(Arrays.asList(items));
                        holdings.setHolding(Arrays.asList(holding));
                        holdingsList.add(holdings);
                    }
                }
            }
        }
        return holdingsList;
    }

    /**
     * Prepares item records from item entities.
     * @param itemEntities
     * @return
     */
    private Items getItems(List<ItemEntity> itemEntities) {
        Items items = new Items();
        ContentType itemContentType = new ContentType();
        CollectionType collectionType = new CollectionType();
        collectionType.setRecord(buildRecordTypes(itemEntities));
        itemContentType.setCollection(collectionType);
        items.setContent(itemContentType);
        return items;
    }

    /**
     * Builds RecordType object from item entities for preparing bib records.
     * @param itemEntities
     * @return
     */
    private List<RecordType> buildRecordTypes(List<ItemEntity> itemEntities) {
        List<RecordType> recordTypes = new ArrayList<>();
        if (itemEntities!=null) {
            for (ItemEntity itemEntity : itemEntities) {
                if(!itemEntity.getCollectionGroupEntity().getCollectionGroupCode().equals(ScsbConstants.COLLECTION_GROUP_PRIVATE)) {
                    RecordType recordType = new RecordType();
                    List<DataFieldType> dataFieldTypeList = new ArrayList<>();
                    dataFieldTypeList.add(build876DataField(itemEntity));
                    dataFieldTypeList.add(build900DataField(itemEntity));
                    recordType.setDatafield(dataFieldTypeList);
                    recordTypes.add(recordType);
                }
            }
        }
        return recordTypes;
    }

    /**
     * Build 900 data field from item entity.
     * @param itemEntity
     * @return
     */
    private DataFieldType build900DataField(ItemEntity itemEntity) {
        DataFieldType dataFieldType = new DataFieldType();
        List<SubfieldatafieldType> subfieldatafieldTypes = new ArrayList<>();
        dataFieldType.setTag("900");
        dataFieldType.setInd1(" ");
        dataFieldType.setInd2(" ");
        subfieldatafieldTypes.add(getSubfieldatafieldType("a", itemEntity.getCollectionGroupEntity().getCollectionGroupCode()));
        subfieldatafieldTypes.add(getSubfieldatafieldType("b", itemEntity.getCustomerCode()));
        dataFieldType.setSubfield(subfieldatafieldTypes);
        return dataFieldType;
    }

    /**
     * Build 876 data field from item entity.
     * @param itemEntity
     * @return
     */
    private DataFieldType build876DataField(ItemEntity itemEntity) {
        DataFieldType dataFieldType = new DataFieldType();
        List<SubfieldatafieldType> subfieldatafieldTypes = new ArrayList<>();
        dataFieldType.setTag("876");
        dataFieldType.setInd1(" ");
        dataFieldType.setInd2(" ");
        subfieldatafieldTypes.add(getSubfieldatafieldType("p", itemEntity.getBarcode()));
        subfieldatafieldTypes.add(getSubfieldatafieldType("h", itemEntity.getUseRestrictions() != null ? itemEntity.getUseRestrictions():""));
        subfieldatafieldTypes.add(getSubfieldatafieldType("a", itemEntity.getOwningInstitutionItemId()));
        subfieldatafieldTypes.add(getSubfieldatafieldType("j", itemEntity.getItemStatusEntity() != null ? itemEntity.getItemStatusEntity().getStatusCode():""));
        subfieldatafieldTypes.add(getSubfieldatafieldType("t", itemEntity.getCopyNumber() != null ? itemEntity.getCopyNumber().toString() : ""));
        subfieldatafieldTypes.add(getSubfieldatafieldType("3", itemEntity.getVolumePartYear()));
        subfieldatafieldTypes.add(getSubfieldatafieldType("l", itemEntity.getImsLocationEntity().getImsLocationCode()));
        subfieldatafieldTypes.add(getSubfieldatafieldType("k", itemEntity.getItemLibrary() != null ? itemEntity.getItemLibrary():""));
        dataFieldType.setSubfield(subfieldatafieldTypes);
        return dataFieldType;
    }

    /**
     * Builds sub field for the given code and value.
     * @param code
     * @param value
     * @return
     */
    private SubfieldatafieldType getSubfieldatafieldType(String code, String value) {
        SubfieldatafieldType subfieldatafieldType = new SubfieldatafieldType();
        subfieldatafieldType.setCode(code);
        subfieldatafieldType.setValue(value);
        return subfieldatafieldType;
    }

    /**
     * Builds ContentType object from byte array marc content to prepare bib records.
     * @param byteContent
     * @return
     * @throws Exception
     */
    private ContentType getContentType(byte[] byteContent) throws Exception{
        String content = new String(byteContent, StandardCharsets.UTF_8);
        CollectionType collectionType;
        JAXBContext context = JAXBContext.newInstance(CollectionType.class);
        XMLInputFactory xif = XMLInputFactory.newFactory();
        xif.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
        InputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        XMLStreamReader xsr = xif.createXMLStreamReader(stream);
        Unmarshaller um = context.createUnmarshaller();
        collectionType = (CollectionType) um.unmarshal(xsr);
        ContentType contentType = new ContentType();
        contentType.setCollection(collectionType);
        return contentType;
    }

    private  DataFieldType  add901Field(List<RecordType> record, BibliographicEntity bibliographicEntity) {
        DataFieldType dataFieldType = new DataFieldType();
        List<SubfieldatafieldType> subfieldatafieldTypes = new ArrayList<>();
        dataFieldType.setTag(ScsbConstants.MarcFields.DF_901);
        dataFieldType.setInd1(" ");
        dataFieldType.setInd2(" ");
        subfieldatafieldTypes.add(getSubfieldatafieldType("a", bibliographicEntity.getMatchingIdentity() != null ? bibliographicEntity.getMatchingIdentity() : ""));
        subfieldatafieldTypes.add(getSubfieldatafieldType("b", bibliographicEntity.getMatchingIdentity() != null ? String.valueOf(bibliographicEntity.getMatchScore()) : ""));
        subfieldatafieldTypes.add(getSubfieldatafieldType("c", bibliographicEntity.getMatchingIdentity() != null ? String.valueOf(bibliographicEntity.isAnamolyFlag()) : ""));
        dataFieldType.setSubfield(subfieldatafieldTypes);
        return dataFieldType;
    }

}
